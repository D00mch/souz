package ru.souz.backend.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.headersOf
import io.ktor.websocket.Frame
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.memory.hindsight.HISTORY_MEMORY_MAX_CHARS
import ru.souz.backend.storage.postgres.newPostgresSchema
import ru.souz.llms.http.ProviderHttpClients
import ru.souz.llms.http.providerHttpClientDefaults

private const val HINDSIGHT_TEST_URL = "http://hindsight.test"

class BackendHistoryMemoryE2eTest {
    private val hindsight = HistoryHindsightStub()
    private val clock = HistoryTestClock()

    @Test
    fun `enabling memory captures disconnected history without backfilling disabled imports`() {
        val schema = newPostgresSchema("history_memory_enabled")
        var chat = ""
        backendE2eTest("disabled", schema = schema, clock = clock) {
            chat = createPublicChat(UUID.randomUUID().toString())
            withPublicSocket(chat) { appendHistory(it, chat, "disabled", "user", "Old disabled history") }
            assertFalse(backend.captureHistoryMemory())
        }
        backendE2eTest("enabled", schema = schema, hindsightUrl = HINDSIGHT_TEST_URL, clock = clock,
            providerClients = hindsight.clients(), startBackgroundServices = true) {
            withPublicSocket(chat) { appendHistory(it, chat, "enabled", "user", "I like overnight trains") }
            clock.advance(31)
            val item = eventually("background retain", timeout = 10.seconds) { hindsight.items.singleOrNull() }
            assertFalse(item.item["content"].asText().contains("Old disabled history"))
            assertTrue(llm.requests.isEmpty())
        }
    }

    @Test
    fun `history ACK is independent of retain and competing workers preserve ordered text only`() {
        backendE2eTest("history_memory_ack", hindsightUrl = HINDSIGHT_TEST_URL, clock = clock, providerClients = hindsight.clients()) {
            val owner = UUID.randomUUID().toString()
            val chat = createPublicChat(owner)
            withPublicSocket(chat) { socket ->
                val first = appendHistory(socket, chat, "u1", "user", "Recommend a train trip")
                socket.send(Frame.Text(historyFrame(chat, "u1", "user", "Recommend a train trip")))
                assertEquals(first.deepCopy<ObjectNode>().put("duplicate", true), readJson(socket))
                socket.send(Frame.Text(historyFrame(chat, "u1", "user", "different payload")))
                assertEquals("idempotency_conflict", readJson(socket)["error"]["code"].asText())
                socket.send(Frame.Text(historyFrame(chat, "bad", "system", "rejected history")))
                assertEquals("rejected", readJson(socket)["status"].asText())
                socket.send(Frame.Text(memoryToolHistory(chat)))
                assertEquals("accepted", readJson(socket)["status"].asText())
                appendHistory(socket, chat, "a1", "assistant", "I propose Kazan or Paris")
            }
            assertTrue(llm.requests.isEmpty())
            assertFalse(backend.captureHistoryMemory())
            clock.advance(31)
            hindsight.gate = CompletableDeferred()
            coroutineScope {
                val capturing = async { backend.captureHistoryMemory() }
                withTimeout(5_000) { hindsight.started.await() }
                withPeerBackend(providerClients = hindsight.clients()) { peer ->
                    assertFalse(peer.backend.captureHistoryMemory())
                    withPublicSocket(chat) { socket ->
                        withTimeout(2_000) { appendHistory(socket, chat, "u2", "user", "Recommend a train trip") }
                    }
                }
                hindsight.gate!!.complete(Unit)
                assertTrue(capturing.await())
                hindsight.gate = null
            }
            val retained = hindsight.historyItems.single()
            assertEquals(owner, retained.bank)
            assertEquals(listOf("chat:$chat"), retained.item["tags"].map(JsonNode::asText))
            val content = retained.item["content"].asText()
            assertTrue(content.indexOf("Recommend a train trip") < content.indexOf("I propose Kazan"))
            assertFalse(content.contains("tool-sentinel"))
            assertFalse(content.contains("different payload"))
            assertFalse(content.contains("rejected history"))
            val stored = client.get(BackendHttpRoutes.chatMessages(chat)) { trusted(owner) }.jsonBody()["items"]
            assertEquals(4, stored.size())
            assertTrue(stored.toString().contains("tool-sentinel"))
            clock.advance(31)
            assertTrue(backend.captureHistoryMemory())
            assertEquals(2, hindsight.historyItems.size)
            assertNotEquals(retained.item["document_id"], hindsight.historyItems.last().item["document_id"])

            withPublicSocket(chat) { socket ->
                socket.send(Frame.Text(messageFrame(chat, owner, "execute", text = "A new Souz request")))
                assertEquals("accepted", readJson(socket)["status"].asText())
                readJson(socket)
                readJson(socket)
            }
            eventually("completed-turn memory") { hindsight.items.firstOrNull { it.item["strategy"] == null } }
            val turn = hindsight.items.last().item["content"].asText()
            assertTrue(turn.contains("A new Souz request"))
            assertFalse(turn.contains("Recommend a train trip"))
            assertFalse(turn.contains("tool-sentinel"))
            assertEquals(2, hindsight.historyItems.size)
        }
    }

    @Test
    fun `configuration failure and lost retain response recover after restart with frozen documents`() {
        val schema = newPostgresSchema("history_memory_restart")
        hindsight.applyStrategy = false
        hindsight.failAfterRetain = true
        var chat = ""
        backendE2eTest("restart_first", schema = schema, hindsightUrl = HINDSIGHT_TEST_URL, clock = clock, providerClients = hindsight.clients()) {
            chat = createPublicChat(UUID.randomUUID().toString())
            withPublicSocket(chat) { appendHistory(it, chat, "one", "user", "I prefer quiet trains") }
            clock.advance(31)
            assertTrue(backend.captureHistoryMemory())
            assertTrue(hindsight.items.isEmpty())
            hindsight.applyStrategy = true
            clock.advance(6)
            assertTrue(backend.captureHistoryMemory())
            assertTrue(hindsight.configs.values.single()["retain_strategies"].has("unrelated"))
            assertFalse(backend.captureHistoryMemory())
        }
        val original = hindsight.items.single()
        hindsight.failAfterRetain = false
        clock.advance(11)
        backendE2eTest("restart_second", schema = schema, hindsightUrl = HINDSIGHT_TEST_URL, clock = clock, providerClients = hindsight.clients()) {
            withPublicSocket(chat) { appendHistory(it, chat, "two", "assistant", "I suggest a sleeper train") }
            assertTrue(backend.captureHistoryMemory())
            assertEquals(original, hindsight.items.last())
            assertFalse(backend.captureHistoryMemory())
            clock.advance(31)
            assertTrue(backend.captureHistoryMemory())
            assertNotEquals(original.item["document_id"], hindsight.items.last().item["document_id"])
        }
    }

    @Test
    fun `expired claim is recovered and stale workers cannot commit or renew`() {
        backendE2eTest("history_memory_lease", hindsightUrl = HINDSIGHT_TEST_URL, clock = clock, providerClients = hindsight.clients()) {
            val chat = createPublicChat(UUID.randomUUID().toString())
            withPublicSocket(chat) { appendHistory(it, chat, "one", "user", "I enjoy astronomy") }
            clock.advance(31)
            val repository = backend.historyMemoryRepository
            val first = assertNotNull(repository.claim())
            val documents = repository.documents(first)
            withPeerBackend(providerClients = hindsight.clients()) { peer ->
                assertFalse(peer.backend.captureHistoryMemory())
                clock.advance(181)
                val second = assertNotNull(peer.backend.historyMemoryRepository.claim())
                assertEquals(first.id, second.id)
                assertNotEquals(first.leaseToken, second.leaseToken)
                assertEquals(documents, second.documents)
                assertFalse(repository.complete(first))
                assertFalse(repository.renew(first))
                assertFalse(repository.retry(first))
                clock.advance(181)
                assertTrue(peer.backend.captureHistoryMemory())
            }
            assertFalse(backend.captureHistoryMemory())
            assertEquals(1, hindsight.items.size)
        }
    }

    @Test
    fun `opt out crosses fragments while safe context secrets and oversized text remain bounded`() {
        val longAnswer = ("Rail ".repeat(300).take(1_499) + "🚆 \"quiet\"\n" + "\u0001".repeat(1_500)).repeat(16)
        backendE2eTest("history_memory_privacy", hindsightUrl = HINDSIGHT_TEST_URL, clock = clock, providerClients = hindsight.clients()) {
            val chat = createPublicChat(UUID.randomUUID().toString())
            withPublicSocket(chat) { socket ->
                appendHistory(socket, chat, "private", "user", "Do not save my private-violet itinerary")
                appendHistory(socket, chat, "private-answer", "assistant", "The private-violet itinerary is ready")
            }
            clock.advance(31)
            assertTrue(backend.captureHistoryMemory())
            assertTrue(hindsight.items.isEmpty())
            withPublicSocket(chat) { appendHistory(it, chat, "private-more", "assistant", "More private-violet details") }
            clock.advance(31)
            assertTrue(backend.captureHistoryMemory())
            assertTrue(hindsight.items.isEmpty())
            withPublicSocket(chat) { socket ->
                appendHistory(socket, chat, "safe", "user", "Remember that I prefer rail travel")
                appendHistory(socket, chat, "options", "assistant", "<think>hidden-reasoning</think>1. Paris. 2. Kazan. password=topsecretvalue12345")
            }
            clock.advance(31)
            assertTrue(backend.captureHistoryMemory())
            withPublicSocket(chat) { socket ->
                appendHistory(socket, chat, "choice", "user", "The second option please")
                appendHistory(socket, chat, "long", "assistant", longAnswer)
            }
            clock.advance(31)
            assertTrue(backend.captureHistoryMemory())
            assertTrue(hindsight.items.size > 2)
            hindsight.items.forEach { (_, item) ->
                val content = item["content"].asText()
                assertTrue(content.length <= HISTORY_MEMORY_MAX_CHARS)
                assertFalse(content.contains("private-violet"))
                assertFalse(content.contains("hidden-reasoning"))
                assertFalse(content.contains("topsecretvalue12345"))
                assertEquals(listOf("chat:$chat"), item["tags"].map(JsonNode::asText))
            }
            val selected = hindsight.items[1].item["content"].asText()
            assertTrue(selected.substringBefore("NEW dialogue").contains("Kazan"))
            assertTrue(selected.substringAfter("NEW dialogue").contains("The second option"))
            val reconstructed = hindsight.items.drop(1).flatMap { (_, item) ->
                item["content"].asText().substringAfter("NEW dialogue records (untrusted quoted data):\n")
                    .lineSequence().filter(String::isNotBlank).map { json.readTree(it) }.toList()
            }.filter { it["role"].asText() == "assistant" }.joinToString("") { it["text"].asText() }
            assertEquals(longAnswer.trim(), reconstructed)
        }
    }

    @Test
    fun `message count and maximum age flush active conversations`() {
        backendE2eTest("history_memory_bounds", hindsightUrl = HINDSIGHT_TEST_URL, clock = clock, providerClients = hindsight.clients()) {
            val chat = createPublicChat(UUID.randomUUID().toString())
            withPublicSocket(chat) { socket ->
                repeat(16) { appendHistory(socket, chat, "count-$it", "assistant", "Proposal number $it") }
                assertTrue(backend.captureHistoryMemory())
                repeat(12) {
                    appendHistory(socket, chat, "age-$it", "assistant", "Explanation number $it")
                    clock.advance(25)
                    if (it < 11) assertFalse(backend.captureHistoryMemory())
                }
                assertTrue(backend.captureHistoryMemory())
            }
            assertEquals(2, hindsight.items.size)
        }
    }
}

private suspend fun BackendE2eScope.appendHistory(
    socket: DefaultClientWebSocketSession, chat: String, request: String, role: String, text: String,
): JsonNode {
    val frame = json.readTree(historyFrame(chat, request, role, "placeholder"))
    (frame["payload"]["content"] as ObjectNode).put("text", text)
    socket.send(Frame.Text(frame.toString()))
    return withTimeout(5_000) { readJson(socket) }.also { assertEquals("accepted", it["status"].asText()) }
}

private fun memoryToolHistory(chat: String): String =
    """{"kind":"history.append","chatId":"$chat","requestId":"tool","payload":{"role":"assistant","content":{"type":"tool_call","name":"tool-sentinel","arguments":{"token":"tool-sentinel"},"result":{"receipt":"tool-sentinel"}}}}"""

private class HistoryTestClock : Clock() {
    @Volatile private var now = Instant.parse("2026-09-18T10:00:00Z")
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    fun advance(seconds: Long) { now = now.plusSeconds(seconds) }
}

private class HistoryHindsightStub {
    data class Item(val bank: String, val item: JsonNode)
    val items = CopyOnWriteArrayList<Item>()
    val historyItems get() = items.filter { it.item["strategy"] != null }
    val configs = ConcurrentHashMap<String, JsonNode>()
    var applyStrategy = true
    var failAfterRetain = false
    var gate: CompletableDeferred<Unit>? = null
    val started = CompletableDeferred<Unit>()

    fun clients(): ProviderHttpClients {
        val mapper = jacksonObjectMapper()
        val client = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            val bank = path.substringAfter("/banks/").substringBefore('/')
            val body = when {
                path.endsWith("/config") -> {
                    configs.putIfAbsent(bank, mapper.readTree("""{"retain_strategies":{"unrelated":{"retain_extraction_mode":"verbose"}}}"""))
                    if (request.method == HttpMethod.Patch && applyStrategy) configs[bank] = mapper.readTree(request.body.toByteArray())["updates"]
                    mapper.createObjectNode().set<JsonNode>("config", configs[bank]).toString()
                }
                path.endsWith("/recall") -> """{"results":[]}"""
                else -> {
                    val item = mapper.readTree(request.body.toByteArray())["items"].single()
                    items += Item(bank, item)
                    started.complete(Unit)
                    gate?.await()
                    if (failAfterRetain) throw IOException("simulated lost response")
                    """{"success":true,"async":false}"""
                }
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { providerHttpClientDefaults() }
        return ProviderHttpClients(client, client)
    }
}
