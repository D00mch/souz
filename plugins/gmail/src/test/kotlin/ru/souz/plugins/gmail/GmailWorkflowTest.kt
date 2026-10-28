package ru.souz.plugins.gmail

import com.fasterxml.jackson.databind.JsonNode
import java.util.Base64
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import ru.souz.llms.ToolInvocationMeta
import ru.souz.plugins.*
import ru.souz.skilloauth.ApiCallRequest
import ru.souz.skilloauth.ApiCallResponse

class GmailWorkflowTest {
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
        assertEquals("uncertain", host.call("deliver", args + ("text" to "duplicate"))["status"].asText())
        sender.cancelAndJoin()
        assertEquals(1, host.sent.size)
        assertEquals("uncertain", host.call("status")["pending"]["m1"]["status"].asText())
        host.time += 700_000
        assertTrue(host.call("sync")["message"].isNull)
        assertEquals("resolved", host.call("resolve_uncertain", args + ("outcome" to "delivered"))["status"].asText())
        assertEquals(listOf("m1"), host.call("status")["done"].map { it.asText() })
    }

    @Test fun `pagination preserves baseline watch cannot heal gaps and stale responses conflict`() = runTest {
        val host = Mailbox()
        host.configure()
        host.history = """{"nextPageToken":"next","history":[],"historyId":"200"}"""
        host.call("sync")
        assertEquals("100", host.call("status")["historyCursor"].asText())
        host.call("watch")
        assertEquals("100", host.call("status")["historyCursor"].asText())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        host.beforeHistory = { entered.complete(Unit); release.await() }
        val stale = async { host.call("sync") }
        entered.await()
        host.call("restart_pages", mapOf("revision" to host.call("status")["revision"].asLong()))
        release.complete(Unit)
        assertEquals("conflict", stale.await()["status"].asText())
        host.beforeHistory = {}
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
}

private class Mailbox : PluginContext {
    var time = 1_000L
    var history = """{"historyId":"101","history":[{"messagesAdded":[{"message":{"id":"m1"}}]}]}"""
    var historyCode = 200
    var beforeHistory: suspend () -> Unit = {}
    var readFails = false
    var send: suspend () -> Boolean = { true }
    val sent = mutableListOf<String>()
    val filesByPath = mutableMapOf<String, String>()
    var attachment = "%PDF-1.7".toByteArray()
    var attachmentSize: Int? = null
    var saved: ByteArray? = null
    override val meta = ToolInvocationMeta("owner")
    override val http: PluginHttp get() = error("Gmail must use OAuth")
    override val channels = PluginChannels { type, id, text ->
        assertEquals("chat", type); assertEquals("owned", id)
        sent += text
        send()
    }
    override val oauth = PluginOAuth { _, scopes, request ->
        assertEquals(setOf("https://www.googleapis.com/auth/gmail.readonly"), scopes)
        response(request)
    }
    override val files = object : PluginFiles {
        override suspend fun readText(path: String) = filesByPath[path]
        override suspend fun save(extension: String, content: ByteArray): String {
            saved = content
            return "/generated/attachment.$extension"
        }
    }
    // Only the host storage boundary is doubled; Gmail transitions execute unchanged.
    override val data = object : PluginData {
        private val mutex = Mutex()
        private val records = mutableMapOf<String, JsonNode>()
        override suspend fun get(key: String): JsonNode? = mutex.withLock { records[key]?.deepCopy() }
        override suspend fun <R> update(key: String, transform: (JsonNode?) -> DataChange<R>): R = mutex.withLock {
            val change = transform(records[key]?.deepCopy())
            if (change.value == null) records.remove(key) else records[key] = change.value!!.deepCopy()
            change.result
        }
        override suspend fun list(after: String?, limit: Int): DataPage = error("Unused by Gmail")
    }
    override fun log(message: String) {}
    suspend fun call(command: String, args: Map<String, Any> = emptyMap()): JsonNode =
        GmailFlow(this) { time }.execute(command, tree(mapOf("config" to "personal") + args))
    suspend fun configure(mode: String = "subject") = call("configure", mapOf("mailboxEmail" to "owner@example.com",
        "topicName" to "projects/demo/topics/mail", "channelType" to "chat", "channelId" to "owned", "notificationMode" to mode))

    private suspend fun response(request: ApiCallRequest): ApiCallResponse {
        val path = request.url.substringBefore('?')
        return when {
            path.endsWith("/profile") -> ApiCallResponse(200, """{"emailAddress":"owner@example.com","historyId":"100"}""")
            path.endsWith("/history") -> { beforeHistory(); ApiCallResponse(historyCode, history) }
            path.endsWith("/watch") -> ApiCallResponse(200, """{"historyId":"999","expiration":"9999999999"}""")
            "/attachments/" in path -> ApiCallResponse(200, tree(mapOf("size" to (attachmentSize ?: attachment.size), "data" to Base64.getUrlEncoder().encodeToString(attachment))).toString())
            else -> ApiCallResponse(if (readFails) 503 else 200, tree(mapOf("id" to "m1", "internalDate" to "9999999999999", "labelIds" to listOf("INBOX"),
                "payload" to mapOf("mimeType" to "text/plain", "headers" to listOf(mapOf("name" to "Subject", "value" to "Invoice")), "body" to mapOf("data" to encoded("Hello"))))).toString())
        }
    }
}

private fun JsonNode.fieldsMap(): Map<String, Any> = properties().associate { it.key to it.value.asText() }
private fun encoded(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
