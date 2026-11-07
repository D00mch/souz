package ru.souz.plugins.gmail

import com.fasterxml.jackson.databind.JsonNode
import java.util.Base64
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import ru.souz.plugins.api.*

class GmailWorkflowTest {
    @Test fun `large history pages make durable lossless progress across page restarts`() = runTest {
        val host = Mailbox()
        host.configure()
        val first = (0 until 501).map { "first_$it" }
        val second = first.take(25) + (0 until 601).map { "second_$it" }
        host.historyFor = { request ->
            val last = "pageToken=next" in request.url
            tree(mapOf("historyId" to "200", "history" to listOf(mapOf("messagesAdded" to
                (if (last) second else first).map { mapOf("message" to mapOf("id" to it)) }))) +
                (if (last) emptyMap() else mapOf("nextPageToken" to "next"))).toString()
        }
        val delivered = mutableSetOf<String>()
        var restarted = false
        for (attempt in 0 until first.size + second.size + 10) {
            val sync = host.call("sync")
            if (!sync["message"].isNull) {
                val inputs = sync["message"].fieldsMap()
                assertTrue(delivered.add(inputs.getValue("message").toString()), "A completed message was claimed twice")
                host.call("read", inputs)
                host.call("deliver", inputs)
            }
            if (!restarted && host.call("status")["pageToken"].asText() == "next") {
                val revision = host.call("status")["revision"].asLong()
                host.call("restart_pages", mapOf("revision" to revision))
                restarted = true
            }
            if (delivered.size == (first + second).distinct().size && sync["message"].isNull && !sync["moreHistory"].asBoolean()) break
        }
        assertEquals((first + second).toSet(), delivered)
        assertTrue(restarted)
        assertEquals("200", host.call("status")["historyCursor"].asText())
        assertEquals(delivered.size, host.sent.size)
        assertTrue(host.largestState <= PluginApi.MAX_JSON_BYTES)
    }

    @Test fun `near limit legacy state migrates before long pagination and retains evicted deduplication`() = runTest {
        val host = Mailbox()
        host.configure()
        val old = (0 until 10_000).map { "old_" + it.toString().padStart(96, '0') }
        val saved = json.readValue(host.state.get("connection:personal"), GmailRecord::class.java)
        host.state.put("connection:personal", json.writeValueAsString(saved.copy(state = GmailState(
            status = Phase.ready, historyCursor = "100", startedAtMillis = 1, pageToken = "next", done = old))))
        assertTrue(host.largestState > 1_000_000)
        val writesBefore = host.writeCount
        host.call("status")
        assertEquals(2, host.writeCount - writesBefore, "Legacy migration needs one immutable index write and one checkpoint write")
        val store = GmailStore(host.state) { host.time }
        repeat(300) { index -> store.change("personal") { _, state ->
            state.finish("new_" + index.toString().padStart(124, '0')) to Unit
        } }
        val current = host.call("status")
        assertTrue(current["done"].size() <= 2_000)
        assertEquals("next", current["pageToken"].asText())
        assertTrue(store.unseen("personal", old + listOf("fresh"), store.snapshot("personal").state) == listOf("fresh"))
        assertTrue(host.state.get("connection:personal")!!.toByteArray().size < 400_000)
        host.history = tree(mapOf("historyId" to "200", "history" to listOf(mapOf("messagesAdded" to
            (old.take(2001) + "fresh").map { mapOf("message" to mapOf("id" to it)) })))).toString()
        repeat(20) { assertTrue(host.call("sync")["message"].isNull) }
        assertEquals(2000, host.call("status")["pageOffset"].asInt())
        assertEquals("fresh", host.call("sync")["message"]["message"].asText())
    }

    @Test fun `failed reads retry and expired unsent claims fence stale callers`() = runTest {
        val host = Mailbox()
        host.configure()
        val first = host.call("sync")["message"]
        host.readFails = true
        assertFailsWith<GmailApiFailure> { host.call("read", first.fieldsMap()) }
        host.readFails = false
        assertEquals("prepared", host.call("read", first.fieldsMap())["status"].asText())
        host.time += 600_001
        val second = host.call("sync")["message"]
        assertNotEquals(first["claim"], second["claim"])
        assertEquals("gmail_claim_inactive", assertFailsWith<PluginException> { host.call("deliver", first.fieldsMap()) }.code)
        assertEquals("gmail_read_required", assertFailsWith<PluginException> { host.call("deliver", second.fieldsMap()) }.code)
        host.call("read", second.fieldsMap())
        assertEquals("gmail_fixed_notification_text", assertFailsWith<PluginException> { host.call("deliver", second.fieldsMap() + ("text" to "forged")) }.code)
        assertEquals("delivered", host.call("deliver", second.fieldsMap())["status"].asText())
        assertEquals(listOf("В Gmail новое письмо. Тема: Invoice"), host.sent)
    }

    @Test fun `concurrent delivery reserves one send and cancellation leaves uncertainty`() = runTest {
        val host = Mailbox()
        host.configure("assistant")
        val args = host.call("sync")["message"].fieldsMap()
        assertEquals("compose", host.call("read", args)["status"].asText())
        val entered = CompletableDeferred<Unit>()
        host.send = { entered.complete(Unit); awaitCancellation() }
        val sender = launch { host.call("deliver", args + ("text" to "Your invoice arrived")) }
        entered.await()
        val duplicate = async { host.call("deliver", args + ("text" to "duplicate")) }
        yield()
        assertFalse(duplicate.isCompleted)
        sender.cancelAndJoin()
        assertEquals("uncertain", duplicate.await()["status"].asText())
        assertEquals(1, host.sent.size)
        assertEquals("uncertain", host.call("status")["pending"]["m1"]["status"].asText())
        host.time += 700_000
        assertTrue(host.call("sync")["message"].isNull)
        assertEquals("resolved", host.call("resolve_uncertain", args + ("outcome" to "delivered"))["status"].asText())
        assertEquals(listOf("m1"), host.call("status")["done"].map { it.asText() })
    }

    @Test fun `pagination preserves baseline watch cannot heal gaps and stale revisions conflict`() = runTest {
        val host = Mailbox()
        host.configure()
        host.history = """{"nextPageToken":"next","history":[],"historyId":"200"}"""
        host.call("sync")
        assertEquals("100", host.call("status")["historyCursor"].asText())
        host.call("watch")
        assertEquals("100", host.call("status")["historyCursor"].asText())
        val revision = host.call("status")["revision"].asLong()
        host.call("restart_pages", mapOf("revision" to revision))
        assertEquals("conflict", host.call("restart_pages", mapOf("revision" to revision))["status"].asText())
        host.history = """{"historyId":"90","history":[{"messagesAdded":[{"message":{"id":"m1"}},{"message":{"id":"m1"}}]}]}"""
        val claim = host.call("sync")["message"].fieldsMap()
        assertEquals("100", host.call("status")["historyCursor"].asText())
        host.call("read", claim); host.call("deliver", claim)
        assertTrue(host.call("sync")["message"].isNull)
        host.historyCode = 404
        assertEquals("resync_required", host.call("sync")["status"].asText())
        assertFailsWith<GmailPaused> { host.call("watch") }
        assertEquals("100", host.call("status")["historyCursor"].asText())
    }

    @Test fun `Python import preserves cursors deduplication and marks in flight claims uncertain`() = runTest {
        val host = Mailbox()
        host.filesByPath["/old/connection.json"] = """{"mailboxEmail":"owner@example.com","topicName":"projects/demo/topics/mail","destination":{"channelType":"chat","channelId":"owned"},"notificationMode":"signal"}"""
        host.filesByPath["/old/state.json"] = """{"schemaVersion":1,"revision":9,"status":"ready","mailboxEmail":"owner@example.com","historyCursor":"77","startedAtMillis":1,"pending":{"m1":{"status":"claimed","claim":"old","expiresAt":99}},"done":["m0"]}"""
        assertEquals(1, host.call("import_connection", mapOf("source" to "/old/connection.json"))["uncertainCount"].asInt())
        val state = host.call("status")
        assertEquals("77", state["historyCursor"].asText())
        assertEquals("m0", state["done"][0].asText())
        assertEquals("gmail_connection_exists", assertFailsWith<PluginException> { host.call("import_connection", mapOf("source" to "/old/connection.json")) }.code)
        assertEquals(2, host.filesByPath.size)
        host.call("resolve_uncertain", mapOf("message" to "m1", "claim" to "old", "outcome" to "skipped"))
        assertTrue(host.call("status")["pending"].isEmpty)
    }

    @Test fun `workspace import preserves pagination watch and history gaps without partial invalid imports`() = runTest {
        val host = Mailbox()
        val source = "/old/connection.json"
        val connection = """{"mailboxEmail":"owner@example.com","topicName":"projects/demo/topics/mail","destination":{"channelType":"chat","channelId":"owned"}}"""
        host.filesByPath[source] = "{"
        assertEquals(PluginFailureKind.BUSINESS, assertFailsWith<PluginException> {
            host.call("import_connection", mapOf("source" to source))
        }.kind)
        assertNull(host.state.get("connection:personal"))
        host.filesByPath[source] = connection
        host.filesByPath["/old/state.json"] = "{"
        assertEquals("gmail_invalid_import", assertFailsWith<PluginException> {
            host.call("import_connection", mapOf("source" to source))
        }.code)
        assertNull(host.state.get("connection:personal"))
        host.filesByPath["/old/state.json"] = """{
            "schemaVersion":1,"revision":9,"status":"ready","mailboxEmail":"owner@example.com",
            "historyCursor":"77","startedAtMillis":1,"historyGap":true,"pageToken":"next","watchExpiresAt":999999,
            "pending":{"m0":{"status":"pending"},"m1":{"status":"claimed","claim":"c1","expiresAt":99},
              "m2":{"status":"delivering","claim":"c2","expiresAt":99},"m3":{"status":"uncertain","claim":"c3","expiresAt":99}},
            "done":["previous"]} """
        assertEquals(3, host.call("import_connection", mapOf("source" to source))["uncertainCount"].asInt())
        val state = host.call("status")
        assertEquals("resync_required", state["status"].asText())
        assertEquals("77", state["historyCursor"].asText())
        assertEquals("next", state["pageToken"].asText())
        assertEquals(999999, state["watchExpiresAt"].asInt())
        assertEquals("pending", state["pending"]["m0"]["status"].asText())
        for (message in listOf("m1", "m2", "m3")) assertEquals("uncertain", state["pending"][message]["status"].asText())
        assertEquals(listOf("previous"), state["done"].map { it.asText() })
        assertEquals("resync_required", assertFailsWith<GmailPaused> { host.call("sync") }.status)
        assertEquals(connection, host.filesByPath[source])
        assertTrue(host.sent.isEmpty())
    }

    @Test fun `mail content prefers plain text excludes attachments and reports bounded UTF8`() {
        val parts = listOf(
            mapOf("mimeType" to "text/html", "body" to mapOf("data" to encoded("<script>bad()</script><p>HTML &amp; text</p>"))),
            mapOf("mimeType" to "text/plain", "body" to mapOf("data" to encoded("Привет ".repeat(2000)))),
            mapOf("mimeType" to "text/plain", "filename" to "secret.txt", "body" to mapOf("data" to encoded("attachment text"), "attachmentId" to "a1", "size" to 15)),
        )
        val content = emailContext(tree(mapOf("payload" to mapOf("parts" to parts))))
        assertTrue(content["body"].asText().startsWith("Привет"))
        assertTrue(content["body"].asText().toByteArray().size <= 4800)
        assertFalse(content["body"].asText().contains('\uFFFD'))
        assertTrue(content["contentTruncated"].asBoolean())
        assertEquals("a1", content["attachments"][0]["attachment"].asText())
        assertEquals("Привет", cleanHeader("=?UTF-8?B?0J/RgNC40LLQtdGC?=", 100))
        assertEquals("a+b c", cleanHeader("=?UTF-8?Q?a+b_c?=", 100))
        for ((mime, data, expected) in listOf(
            Triple("text/html", encoded("<script>bad()</script><p>HTML &amp; text</p>"), "HTML & text"),
            Triple("text/plain", encoded("plain <b>text</b>"), "plain <b>text</b>"),
            Triple("text/plain", "invalid%base64", "fallback"),
        )) {
            val content = emailContext(tree(mapOf("snippet" to "fallback", "payload" to
                mapOf("mimeType" to mime, "body" to mapOf("data" to data)))))
            assertEquals(expected, content["body"].asText())
            assertTrue(content["untrusted"].asBoolean())
        }
    }

    @Test fun `attachment download validates bytes before writing and returns no base64`() = runTest {
        val host = Mailbox()
        val args = mapOf("message" to "m1", "attachment" to "a1", "filename" to "../../report.pdf")
        val downloaded = host.call("download_attachment", args)
        assertEquals("/generated/attachment.pdf", downloaded["filePath"].asText())
        assertFalse(downloaded.has("data"))
        assertEquals("%PDF-1.7", host.saved!!.toString(Charsets.UTF_8))
        for ((filename, bytes, declaredSize) in listOf(
            Triple("bad.pdf", "wrong".toByteArray(), 5),
            Triple("mismatch.bin", byteArrayOf(1, 2), 3),
            Triple("oversized.bin", byteArrayOf(), 25 * 1024 * 1024 + 1),
        )) {
            host.saved = null
            host.attachment = bytes
            host.attachmentSize = declaredSize
            assertFailsWith<IllegalArgumentException>(filename) { host.call("download_attachment", args + ("filename" to filename)) }
            assertNull(host.saved)
        }
        host.attachmentSize = 0
        assertEquals(0, host.call("download_attachment", args + ("filename" to "empty.txt"))["sizeBytes"].asInt())
    }

    @Test fun `failed or lost delivery preserves its reservation and never retries automatically`() = runTest {
        for (mode in listOf("rejected", "throws", "lease_lost")) {
            val host = Mailbox()
            host.configure()
            val args = host.call("sync")["message"].fieldsMap()
            host.call("read", args)
            host.rejectWrites = true
            assertFailsWith<PluginException> { host.call("deliver", args) }
            assertTrue(host.sent.isEmpty())
            host.rejectWrites = false
            host.send = {
                val persisted = json.readTree(host.state.get("connection:personal"))
                assertEquals("delivering", persisted["state"]["pending"]["m1"]["status"].asText())
                when (mode) {
                    "throws" -> error("Delivery acknowledgement was lost")
                    "lease_lost" -> { host.rejectWrites = true; throw CancellationException("Lease lost") }
                    else -> false
                }
            }
            if (mode == "lease_lost") assertFailsWith<CancellationException> { host.call("deliver", args) }
            else assertEquals("uncertain", host.call("deliver", args)["status"].asText())
            host.rejectWrites = false
            host.send = { error("An uncertain send must not be replayed") }
            assertEquals("uncertain", host.call("deliver", args)["status"].asText())
            host.time += 600_001
            assertTrue(host.call("sync")["message"].isNull)
            assertEquals(1, host.sent.size)
            assertEquals("uncertain", host.call("status")["pending"]["m1"]["status"].asText())
            host.call("resolve_uncertain", args + ("outcome" to "skipped"))
            assertEquals(listOf("m1"), host.call("status")["done"].map { it.asText() })
        }
    }

    @Test fun `attachment above default JSON string limit remains readable within download bound`() = runTest {
        val host = Mailbox()
        host.attachment = ByteArray(16 * 1024 * 1024)
        val downloaded = host.call("download_attachment", mapOf("message" to "m1", "attachment" to "a1", "filename" to "large.bin"))
        assertEquals(host.attachment.size, downloaded["sizeBytes"].asInt())
        assertContentEquals(host.attachment, host.saved)
    }

    @Test fun `independent SDK schemas and results retain reconnect and technical outcomes`() = runTest {
        val plugin = GmailPlugin()
        assertEquals(12, plugin.tools.size)
        plugin.tools.forEach { tool ->
            assertEquals("MAIL", tool.category)
            val schema = json.readTree(tool.inputSchemaJson)
            assertEquals("object", schema["type"].asText())
            assertFalse(schema["additionalProperties"].asBoolean())
        }
        val search = plugin.tools.single { it.name == "search" }
        val host = Mailbox()
        host.reconnect = true
        val reconnect = json.readTree(search.invoke("{\"query\":\"in:inbox\"}", host))
        assertEquals("reconnect_required", reconnect["status"].asText())
        assertEquals("https://consent.example/connect", reconnect["authorizationUrl"].asText())
        host.reconnect = false
        host.readFails = true
        assertEquals(PluginFailureKind.TECHNICAL, assertFailsWith<PluginException> {
            search.invoke("{\"query\":\"in:inbox\"}", host)
        }.kind)
    }
}

private class Mailbox : PluginContext {
    var time = 1_000L
    var history = """{"historyId":"101","history":[{"messagesAdded":[{"message":{"id":"m1"}}]}]}"""
    var historyCode = 200
    var historyFor: ((PluginOAuthRequest) -> String)? = null
    var largestState = 0
    var writeCount = 0
    var readFails = false
    var reconnect = false
    var rejectWrites = false
    var send: suspend () -> Boolean = { true }
    val sent = mutableListOf<String>()
    val filesByPath = mutableMapOf<String, String>()
    var attachment = "%PDF-1.7".toByteArray()
    var attachmentSize: Int? = null
    var saved: ByteArray? = null
    private val invocation = Mutex()
    override val meta = PluginInvocationMeta("owner")
    override val http: PluginHttp get() = error("Gmail must use OAuth")
    override val channels = object : PluginChannels {
        override suspend fun send(type: String, id: String, text: String, fileIds: List<String>): Boolean {
            assertEquals("chat", type); assertEquals("owned", id)
            assertTrue(fileIds.isEmpty())
            sent += text
            return this@Mailbox.send()
        }
    }
    override val oauth = PluginOAuth { _, scopes, request ->
        assertEquals(setOf("https://www.googleapis.com/auth/gmail.readonly"), scopes)
        if (reconnect) PluginReconnectRequired("https://consent.example/connect", "Reconnect") else response(request)
    }
    override val files = object : PluginFiles {
        override suspend fun readWorkspaceText(path: String) = filesByPath[path]
        override suspend fun saveWorkspace(extension: String, content: ByteArray): String {
            saved = content
            return "/generated/attachment.$extension"
        }
        override suspend fun loadStored(fileId: String): PluginFileContent = error("Unused by Gmail")
        override suspend fun saveStored(name: String, content: ByteArray): PluginFile = error("Unused by Gmail")
    }
    // The host serializes complete calls; state operations are short independent writes.
    override val state = object : PluginState {
        private val records = mutableMapOf<String, String>()
        override suspend fun get(key: String): String? = records[key]
        override suspend fun put(key: String, json: String) {
            if (rejectWrites) throw PluginException("plugin_lease_lost", "Invocation is no longer owned")
            val size = json.toByteArray().size
            require(size <= PluginApi.MAX_JSON_BYTES)
            largestState = maxOf(largestState, size)
            writeCount++
            records[key] = json
        }
        override suspend fun delete(key: String): Boolean = records.remove(key) != null
        override suspend fun list(after: String?, limit: Int): PluginStatePage = error("Unused by Gmail")
    }
    override suspend fun log(message: String) {}
    suspend fun call(command: String, args: Map<String, Any> = emptyMap()): JsonNode = invocation.withLock {
        GmailFlow(this) { time }.execute(command, tree(mapOf("config" to "personal") + args))
    }
    suspend fun configure(mode: String = "subject") = call("configure", mapOf("mailboxEmail" to "owner@example.com",
        "topicName" to "projects/demo/topics/mail", "channelType" to "chat", "channelId" to "owned", "notificationMode" to mode))

    private fun response(request: PluginOAuthRequest): PluginOAuthResponse {
        val path = request.url.substringBefore('?')
        return when {
            path.endsWith("/profile") -> PluginOAuthResponse(200, """{"emailAddress":"owner@example.com","historyId":"100"}""")
            path.endsWith("/history") -> PluginOAuthResponse(historyCode, historyFor?.invoke(request) ?: history)
            path.endsWith("/watch") -> PluginOAuthResponse(200, """{"historyId":"999","expiration":"9999999999"}""")
            "/attachments/" in path -> PluginOAuthResponse(200, tree(mapOf("size" to (attachmentSize ?: attachment.size), "data" to Base64.getUrlEncoder().encodeToString(attachment))).toString())
            else -> PluginOAuthResponse(if (readFails) 503 else 200, tree(mapOf("id" to "m1", "internalDate" to "9999999999999", "labelIds" to listOf("INBOX"),
                "payload" to mapOf("mimeType" to "text/plain", "headers" to listOf(mapOf("name" to "Subject", "value" to "Invoice")), "body" to mapOf("data" to encoded("Hello"))))).toString())
        }
    }
}

private fun JsonNode.fieldsMap(): Map<String, Any> = properties().associate { it.key to it.value.asText() }
private fun encoded(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
