package ru.souz.plugins.gmail

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import ru.souz.plugins.PluginCallContext
import ru.souz.plugins.PluginException

internal class GmailFlow(private val context: PluginCallContext, private val now: () -> Long = System::currentTimeMillis) {
    suspend fun execute(command: String, args: ObjectNode): JsonNode = try {
        when (command) {
            "configure" -> configure(args)
            "import_connection" -> importConnection(args)
            "search" -> {
                val limit = args.path("maxResults").asInt(10).also { require(it in 1..100) }
                val query = mutableListOf("q" to args.text("query"), "maxResults" to limit.toString())
                args.get("pageToken")?.asText()?.let { require(it.length <= 2048); query += "pageToken" to it }
                context.gmail("/messages", query).ok()
            }
            "get_message" -> {
                val body = context.gmail("/messages/${messageId(args.text("message"))}", messageQuery(args.path("full").asBoolean())).ok()
                emailContext(body).apply { set<JsonNode>("attachments", attachments(body)) }
            }
            "download_attachment" -> downloadAttachment(context, args)
            else -> connectionCommand(command, args)
        }
    } catch (stop: GmailResult) {
        if (args.has("config") && stop.value.path("status").asText() == "reconnect_required") {
            change(args.text("config")) { _, state -> state.put("status", "reconnect_required"); stop.value }
        }
        stop.value
    }

    private suspend fun configure(args: ObjectNode): JsonNode {
        val connection = json.createObjectNode().apply {
            put("mailboxEmail", args.text("mailboxEmail"))
            put("topicName", args.text("topicName"))
            put("notificationMode", args.path("notificationMode").asText("signal"))
            putObject("destination").put("channelType", args.text("channelType")).put("channelId", args.text("channelId"))
        }
        validateConnection(connection)
        context.data.update(key(args.text("config"))) { existing ->
            if (existing != null) {
                require(existing["connection"] == connection) { "Connection already exists; use a new config name" }
                existing
            } else entry(connection, newState(connection.text("mailboxEmail")))
        }
        return result("configured")
    }

    private suspend fun importConnection(args: ObjectNode): JsonNode {
        val source = args.text("source")
        require(source.startsWith('/') || source.matches(Regex("[A-Za-z]:[/\\\\].*"))) { "Source must be an absolute sandbox path" }
        val connection = json.readTree(requireNotNull(context.files.readText(source))) as ObjectNode
        validateConnection(connection)
        val parent = source.substringBeforeLast('/', source.substringBeforeLast('\\', ""))
        val imported = context.files.readText("$parent/state.json")?.let { json.readTree(it) as ObjectNode }
            ?: newState(connection.text("mailboxEmail"))
        validateState(imported, connection)
        imported.path("pending").forEach { item ->
            if (item.path("status").asText() in setOf("claimed", "delivering")) (item as ObjectNode).put("status", "uncertain")
        }
        context.data.update(key(args.text("config"))) { existing ->
            require(existing == null) { "Connection already exists" }
            entry(connection, imported)
        }
        return result("imported").put("uncertainCount", imported.path("pending").count { it.path("status").asText() == "uncertain" })
    }

    private suspend fun connectionCommand(command: String, args: ObjectNode): JsonNode {
        val config = args.text("config")
        return when (command) {
            "status" -> change(config) { _, state -> state.deepCopy() }
            "sync" -> sync(config)
            "watch" -> {
                val snapshot = profile(config)
                val body = json.createObjectNode().put("topicName", snapshot["connection"]["topicName"].asText()).put("labelFilterBehavior", "include")
                body.putArray("labelIds").add("INBOX")
                val response = context.gmail("/watch", body = body).ok()
                val expiry = response.path("expiration").asText().toLong().also { require(it > now()) }
                change(config) { _, state ->
                    state.put("watchExpiresAt", maxOf(expiry, state.path("watchExpiresAt").asLong()))
                    result("watching").put("watchExpiresAt", state.path("watchExpiresAt").asLong())
                }
            }
            "read" -> read(config, args)
            "deliver" -> deliver(config, args)
            "restart_pages" -> change(config) { _, state ->
                if (state.path("revision").asLong() != args.path("revision").asLong()) result("conflict")
                else { state.putNull("pageToken"); result(state.text("status")) }
            }
            "resolve_uncertain" -> change(config) { _, state ->
                val message = messageId(args.text("message"))
                val pending = state.path("pending").path(message)
                require(pending.path("status").asText() == "uncertain" && pending.path("claim").asText() == args.text("claim"))
                require(args.text("outcome") in setOf("delivered", "skipped"))
                finish(state, message)
                result("resolved")
            }
            else -> throw PluginException("gmail_invalid_input", "Unknown Gmail command")
        }
    }

    private suspend fun profile(config: String): ObjectNode {
        val response = context.gmail("/profile").ok()
        var snapshot = json.createObjectNode()
        val status = change(config) { record, state ->
            if (!response.path("emailAddress").asText().equals(record["connection"]["mailboxEmail"].asText(), ignoreCase = true)) {
                state.put("status", "account_mismatch")
            } else {
                if (state.path("historyCursor").isMissingNode || state.path("historyCursor").isNull) {
                    state.put("historyCursor", historyId(response.path("historyId").asText()))
                    state.put("startedAtMillis", now())
                }
                state.put("status", if (state.path("historyGap").asBoolean() || state.text("status") == "resync_required") "resync_required" else "ready")
            }
            snapshot = record.deepCopy()
            result(state.text("status"))
        }
        if (status.path("status").asText() != "ready") throw GmailResult(status)
        return snapshot
    }

    private suspend fun sync(config: String): JsonNode {
        val snapshot = profile(config)
        val state = snapshot["state"]
        val cursor = state.path("historyCursor").asText()
        val page = state.path("pageToken").takeUnless { it.isNull || it.isMissingNode }?.asText()
        val query = mutableListOf("startHistoryId" to cursor, "historyTypes" to "messageAdded", "labelId" to "INBOX", "maxResults" to "25")
        page?.let { query += "pageToken" to it }
        val (code, body) = context.gmail("/history", query)
        val time = now()
        return change(config) { _, current ->
            when {
                current.text("status") != "ready" -> result(current.text("status"))
                current.path("historyCursor").asText() != cursor || current.path("pageToken").takeUnless { it.isNull || it.isMissingNode }?.asText() != page -> result("conflict")
                code == 404 -> { current.put("historyGap", true).put("status", "resync_required"); result("resync_required") }
                else -> {
                    val pending = current["pending"] as ObjectNode
                    val done = current["done"]
                    val ids = body.path("history").flatMap { it.path("messagesAdded").map { added -> messageId(added.path("message").path("id").asText()) } }
                        .distinct().sorted().filter { !pending.has(it) && done.none { item -> item.asText() == it } }
                    val backlog = pending.size() + ids.size > 500
                    if (!backlog) {
                        ids.forEach { pending.putObject(it).put("status", "pending") }
                        val next = body.path("nextPageToken").asText("").takeIf(String::isNotEmpty)
                        current.put("pageToken", next)
                        if (next == null) {
                            current.put("historyCursor", maxOf(cursor.toBigInteger(), historyId(body.path("historyId").asText()).toBigInteger()).toString())
                            val completed = current["done"] as com.fasterxml.jackson.databind.node.ArrayNode
                            while (completed.size() > 2000) completed.remove(0)
                        }
                    }
                    val next = pending.properties().firstOrNull { it.value.path("status").asText() == "pending" }
                    val message = next?.let { (id, raw) ->
                        val claim = UUID.randomUUID().toString()
                        (raw as ObjectNode).put("status", "claimed").put("claim", claim).put("expiresAt", time / 1000.0 + 600)
                        json.createObjectNode().put("message", id).put("claim", claim)
                    }
                    result("ready").apply {
                        put("watchRequired", current.path("watchExpiresAt").asLong() < time + 86_400_000)
                        put("moreHistory", backlog || !current.path("pageToken").isNull && current.path("pageToken").asText("").isNotEmpty())
                        put("uncertainCount", pending.count { it.path("status").asText() in setOf("uncertain", "delivering") })
                        put("pendingRemaining", pending.count { it.path("status").asText() == "pending" })
                        set<JsonNode>("message", message ?: json.nullNode())
                    }
                }
            }
        }
    }

    // Persist uncertainty in NonCancellable before propagating the original failure, including cancellation.
    @Suppress("SuspendFunSwallowedCancellation")
    private suspend fun read(config: String, args: ObjectNode): JsonNode {
        val message = messageId(args.text("message"))
        val claim = args.text("claim")
        val record = change(config) { entry, state -> claimed(state, message, claim); entry.deepCopy() }
        val mode = record["connection"]["notificationMode"].asText("signal")
        return try {
            val (code, body) = context.gmail("/messages/$message", messageQuery(mode == "assistant"))
            if (code == 404 || body.path("labelIds").none { it.asText() == "INBOX" } ||
                body.path("internalDate").asText().toLong() < record["state"]["startedAtMillis"].asLong()) {
                change(config) { _, state -> claimed(state, message, claim); finish(state, message); result("skipped") }
            } else {
                val inputs = (record["connection"]["destination"] as ObjectNode).deepCopy()
                    .put("config", config).put("message", message).put("claim", claim)
                if (mode == "assistant") result("compose").apply {
                    set<JsonNode>("inputs", inputs); set<JsonNode>("email", emailContext(body))
                } else {
                    val headers = headers(body.path("payload"))
                    var text = "В Gmail новое письмо"
                    if (mode in setOf("subject", "summary")) text += ". Тема: " + cleanHeader(headers["subject"].orEmpty(), 300).ifBlank { "без темы" }
                    if (mode == "summary") text += ". " + cleanHeader(body.path("snippet").asText(), 500)
                    result("prepared").apply { set<JsonNode>("inputs", inputs.put("text", text)) }
                }
            }
        } catch (failure: Throwable) {
            try { withContext(NonCancellable) { uncertain(config, message, claim) } }
            catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    // Cancellation cannot bypass the durable uncertain outcome or be replaced by a cleanup failure.
    @Suppress("SuspendFunSwallowedCancellation")
    private suspend fun deliver(config: String, args: ObjectNode): JsonNode {
        val message = messageId(args.text("message"))
        val claim = args.text("claim")
        val text = args.text("text").also { require(it.toByteArray().size <= 16_384) }
        val reservation = change(config) { record, state ->
            val destination = record["connection"]["destination"]
            require(args.text("channelType") == destination["channelType"].asText() && args.text("channelId") == destination["channelId"].asText()) { "Destination differs from connection" }
            val item = state.path("pending").path(message)
            if (item.path("claim").asText() == claim && item.path("status").asText() in setOf("uncertain", "delivering")) result("uncertain")
            else { claimed(state, message, claim).put("status", "delivering"); result("reserved") }
        }
        if (reservation.path("status").asText() != "reserved") return reservation
        return try {
            val sent = context.channels.send(args.text("channelType"), args.text("channelId"), text)
            withContext(NonCancellable) {
                change(config) { _, state ->
                    val item = state.path("pending").path(message)
                    if (sent && item.path("claim").asText() == claim && item.path("status").asText() == "delivering") {
                        finish(state, message); result("delivered")
                    } else { if (item is ObjectNode && item.path("claim").asText() == claim) item.put("status", "uncertain"); result("uncertain") }
                }
            }
        } catch (cancelled: CancellationException) {
            try { withContext(NonCancellable) { uncertain(config, message, claim) } }
            catch (cleanup: Exception) { cancelled.addSuppressed(cleanup) }
            throw cancelled
        } catch (_: Exception) {
            withContext(NonCancellable) { uncertain(config, message, claim) }
            result("uncertain")
        }
    }

    private suspend fun uncertain(config: String, message: String, claim: String) = change(config) { _, state ->
        val item = state.path("pending").path(message)
        if (item is ObjectNode && item.path("claim").asText() == claim) item.put("status", "uncertain")
        result("uncertain")
    }

    private suspend fun change(config: String, operation: (ObjectNode, ObjectNode) -> ObjectNode): ObjectNode {
        val time = now() / 1000.0
        var output = result("unknown")
        context.data.update(key(config)) { stored ->
            val record = stored?.deepCopy<ObjectNode>() ?: throw PluginException("gmail_connection_missing", "Configure or import this connection first")
            val state = record["state"] as ObjectNode
            val before = record.toString()
            state.path("pending").forEach { item ->
                if (item.path("status").asText() in setOf("claimed", "delivering") && item.path("expiresAt").asDouble() <= time) {
                    (item as ObjectNode).put("status", "uncertain")
                }
            }
            output = operation(record, state)
            if (before != record.toString()) state.put("revision", state.path("revision").asLong() + 1)
            output.put("revision", state.path("revision").asLong())
            record
        }
        return output
    }
}
