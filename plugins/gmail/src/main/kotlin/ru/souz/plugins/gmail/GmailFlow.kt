package ru.souz.plugins.gmail

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import ru.souz.plugins.PluginContext

internal class GmailFlow(private val context: PluginContext, private val now: () -> Long = System::currentTimeMillis) {
    private val api = GmailApi(context.oauth)
    private val store = GmailStore(context.data, now)

    suspend fun execute(command: String, args: ObjectNode): JsonNode = when (command) {
        "configure" -> {
            val connection = Connection(args.text("mailboxEmail"), args.text("topicName"),
                Destination(args.text("channelType"), args.text("channelId")), Mode.valueOf(args.path("notificationMode").asText("signal")))
            validate(connection)
            store.create(args.text("config"), GmailRecord(connection, GmailState()), allowIdentical = true)
            result("configured")
        }
        "import_connection" -> importConnection(args)
        "status" -> tree(store.snapshot(args.text("config")).state)
        "sync" -> sync(args.text("config"))
        "read" -> read(args)
        "deliver" -> deliver(args)
        "watch" -> {
            val config = args.text("config")
            val record = profile(config)
            val response = api.request("/watch", body = tree(mapOf("topicName" to record.connection.topicName,
                "labelFilterBehavior" to "include", "labelIds" to listOf("INBOX"))))
            val expires = response.text("expiration").toLong().also { require(it > now()) }
            store.change(config) { _, state ->
                val next = state.copy(watchExpiresAt = maxOf(state.watchExpiresAt, expires))
                next to result("watching").put("watchExpiresAt", next.watchExpiresAt)
            }
        }
        "search" -> {
            val max = args.path("maxResults").asInt(10).also { require(it in 1..100) }
            val page = args.get("pageToken")?.asText()?.also { require(it.length <= 2048) }
            api.request("/messages", listOf("q" to args.text("query"), "maxResults" to max.toString()) + listOfNotNull(page?.let { "pageToken" to it }))
        }
        "get_message" -> emailContext(api.message(args.text("message"), args.path("full").asBoolean()))
        "download_attachment" -> downloadAttachment(api, context.files, args)
        "restart_pages" -> store.change(args.text("config")) { _, state ->
            if (state.revision != args.path("revision").asLong()) state to result("conflict")
            else state.copy(pageToken = null) to result(state.status.name)
        }
        "resolve_uncertain" -> store.change(args.text("config")) { _, state ->
            val message = messageId(args.text("message"))
            val item = requireNotNull(state.pending[message])
            require(item.status == ClaimPhase.uncertain && item.claim == args.text("claim"))
            require(args.text("outcome") == "delivered" || args.text("outcome") == "skipped")
            state.finish(message) to result("resolved")
        }
        else -> error("Unknown Gmail command")
    }

    private suspend fun importConnection(args: ObjectNode): JsonNode {
        val source = args.text("source")
        require(source.startsWith('/')) { "Expected an absolute sandbox path" }
        val connection = json.readValue(requireNotNull(context.files.readText(source)), Connection::class.java)
        validate(connection)
        val raw = context.files.readText("${source.substringBeforeLast('/')}/state.json")
        val state = raw?.let { importState(json.readTree(it), connection) } ?: GmailState()
        store.create(args.text("config"), GmailRecord(connection, state), allowIdentical = false)
        return result("imported").put("uncertainCount", state.pending.count { it.value.status == ClaimPhase.uncertain })
    }

    private suspend fun profile(config: String): GmailRecord {
        val response = api.request("/profile")
        return store.change(config) { connection, state ->
            if (!connection.mailboxEmail.equals(response.text("emailAddress"), ignoreCase = true)) throw GmailPaused("account_mismatch")
            if (state.status == Phase.resync_required) throw GmailPaused("resync_required")
            val next = if (state.historyCursor != null) state else state.copy(
                status = Phase.ready, historyCursor = historyId(response.text("historyId")), startedAtMillis = now(),
            )
            next to GmailRecord(connection, next)
        }
    }

    private suspend fun sync(config: String): JsonNode {
        val base = profile(config).state
        val cursor = requireNotNull(base.historyCursor)
        val page = try {
            api.request("/history", listOf("startHistoryId" to cursor, "historyTypes" to "messageAdded", "labelId" to "INBOX", "maxResults" to "25") +
                listOfNotNull(base.pageToken?.let { "pageToken" to it })).let(::historyPage)
        } catch (e: GmailApiFailure) { if (e.status == 404) null else throw e }
        return store.change(config) { _, current ->
            when {
                current.status != Phase.ready -> current to result(current.status.name)
                current.historyCursor != cursor || current.pageToken != base.pageToken -> current to result("conflict")
                page == null -> current.copy(status = Phase.resync_required) to result("resync_required")
                else -> {
                    val accepted = current.acceptPage(page)
                    val (next, message) = (accepted ?: current).claimNext(now())
                    next to next.syncResult(message, moreHistory = accepted == null || next.pageToken != null, now = now())
                }
            }
        }
    }

    private suspend fun read(args: ObjectNode): JsonNode {
        val config = args.text("config")
        val message = messageId(args.text("message"))
        val token = args.text("claim")
        val record = store.snapshot(config)
        record.state.claimed(message, token)
        val email = try { api.message(message, record.connection.notificationMode == Mode.assistant) }
            catch (e: GmailApiFailure) { if (e.status == 404) null else throw e }
        return store.change(config) { connection, state ->
            val item = state.claimed(message, token)
            if (email == null || email.path("labelIds").none { it.asText() == "INBOX" } ||
                email.path("internalDate").asText().toLong() < state.startedAtMillis) {
                state.finish(message) to result("skipped")
            } else {
                val text = notificationText(connection.notificationMode, email)
                val output = result(if (text == null) "compose" else "prepared")
                output.set<JsonNode>("inputs", tree(mapOf("config" to config, "message" to message, "claim" to token)))
                if (text == null) output.set<JsonNode>("email", emailContext(email)) else output.put("text", text)
                state.replace(message, item.copy(prepared = true, text = text)) to output
            }
        }
    }

    // Persist uncertainty before propagating cancellation; cleanup failure cannot replace it.
    @Suppress("SuspendFunSwallowedCancellation")
    private suspend fun deliver(args: ObjectNode): JsonNode {
        val config = args.text("config")
        val message = messageId(args.text("message"))
        val token = args.text("claim")
        val delivery = store.change(config) { connection, state ->
            val existing = state.pending[message]
            if (existing?.claim == token && (existing.status == ClaimPhase.delivering || existing.status == ClaimPhase.uncertain)) {
                state to null
            } else {
                val item = state.claimed(message, token)
                gmailRequire(item.prepared, "read_required", "Read the claimed message before delivery")
                val text = if (connection.notificationMode == Mode.assistant) args.text("text") else {
                    gmailRequire(!args.has("text"), "fixed_notification_text", "Only assistant mode accepts text; fixed modes use the prepared notification")
                    requireNotNull(item.text)
                }
                require(text.toByteArray().size <= 16_384)
                state.replace(message, item.copy(status = ClaimPhase.delivering)) to (connection.destination to text)
            }
        } ?: return result("uncertain")
        try {
            val sent = context.channels.send(delivery.first.channelType, delivery.first.channelId, delivery.second)
            return settle(config, message, token, sent)
        } catch (failure: Throwable) {
            try { settle(config, message, token, sent = false) }
            catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            if (failure is CancellationException || failure is Error) throw failure
            return result("uncertain")
        }
    }

    private suspend fun settle(config: String, message: String, token: String, sent: Boolean): JsonNode = withContext(NonCancellable) {
        store.change(config) { _, state ->
            val item = state.pending[message]
            when {
                item?.claim != token -> state to result("uncertain")
                sent && item.status == ClaimPhase.delivering -> state.finish(message) to result("delivered")
                else -> state.replace(message, item.copy(status = ClaimPhase.uncertain)) to result("uncertain")
            }
        }
    }
}

internal class GmailPaused(val status: String) : RuntimeException()

private data class HistoryPage(val messages: List<String>, val nextToken: String?, val finalCursor: String?)

private fun historyPage(response: JsonNode): HistoryPage {
    val token = response.path("nextPageToken").asText("").takeIf { it.isNotEmpty() }
    val messages = response.path("history").flatMap { entry ->
        entry.path("messagesAdded").map { messageId(it.path("message").text("id")) }
    }.distinct()
    return HistoryPage(messages, token, if (token == null) historyId(response.text("historyId")) else null)
}

/** Reject the whole page under backpressure; advance the cursor only on the final page. */
private fun GmailState.acceptPage(page: HistoryPage): GmailState? {
    val unseen = page.messages.filter { it !in pending && it !in done }
    if (pending.size + unseen.size > 500) return null
    return copy(
        pending = pending + unseen.associateWith { Claim() }, pageToken = page.nextToken,
        historyCursor = page.finalCursor?.let { maxOf(requireNotNull(historyCursor).toBigInteger(), it.toBigInteger()).toString() } ?: historyCursor,
        done = if (page.nextToken == null) done.takeLast(2000) else done,
    )
}

private fun GmailState.claimNext(now: Long): Pair<GmailState, String?> {
    val message = pending.entries.firstOrNull { it.value.status == ClaimPhase.pending }?.key ?: return this to null
    return replace(message, claim(now)) to message
}

private fun GmailState.syncResult(message: String?, moreHistory: Boolean, now: Long): ObjectNode = result("ready").apply {
    put("watchRequired", watchExpiresAt < now + 86_400_000)
    put("moreHistory", moreHistory)
    put("pendingRemaining", pending.count { it.value.status == ClaimPhase.pending })
    put("uncertainCount", pending.count { it.value.status == ClaimPhase.uncertain || it.value.status == ClaimPhase.delivering })
    set<JsonNode>("message", message?.let { tree(mapOf("message" to it, "claim" to pending.getValue(it).claim)) } ?: json.nullNode())
}

private fun notificationText(mode: Mode, email: JsonNode): String? {
    if (mode == Mode.assistant) return null
    val subject = cleanHeader(headers(email.path("payload"))["subject"].orEmpty(), 300).ifBlank { "без темы" }
    return when (mode) {
        Mode.signal -> "В Gmail новое письмо"
        Mode.subject -> "В Gmail новое письмо. Тема: $subject"
        Mode.summary -> "В Gmail новое письмо. Тема: $subject. ${cleanHeader(email.path("snippet").asText(), 500)}"
        Mode.assistant -> null
    }
}
