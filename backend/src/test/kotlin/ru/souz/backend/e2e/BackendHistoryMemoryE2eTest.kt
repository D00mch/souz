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
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.websocket.Frame
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.memory.hindsight.FORGET_MEMORY_TOOL_NAMES
import ru.souz.backend.memory.hindsight.HISTORY_MEMORY_MAX_CHARS
import ru.souz.backend.memory.hindsight.MEMORY_MUTATION_NOTICE
import ru.souz.backend.memory.hindsight.ToolForgetMemoryFact
import ru.souz.backend.options.model.Option
import ru.souz.backend.options.model.OptionKind
import ru.souz.backend.options.model.OptionStatus
import ru.souz.backend.storage.postgres.newPostgresSchema
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.http.ProviderHttpClients
import ru.souz.llms.http.providerHttpClientDefaults

private const val HINDSIGHT_TEST_URL = "http://hindsight.test"

class BackendHistoryMemoryE2eTest {
    private val hindsight = HistoryHindsightStub()
    private val clock = HistoryTestClock()

    @Test
    fun `recall flag preserves explicit search on both APIs`() {
        val question = "What do you remember about my travel preferences?"
        val recalled = "The user prefers quiet sleeper trains"
        for (automaticRecall in listOf(false, true)) {
            for (webSocket in listOf(false, true)) {
                val memory = HistoryHindsightStub().apply { recalledText = recalled }
                backendE2eTest("memory_search", hindsightUrl = HINDSIGHT_TEST_URL,
                    featureFlags = BackendFeatureFlags(wsEvents = true, wsAutomaticMemoryRecall = automaticRecall),
                    providerClients = memory.clients(), llm = E2eLlmApi { request ->
                        if (request.messages.any { it.role == LLMMessageRole.function && it.name == "SearchMemory" }) {
                            reply(request, "I found your saved travel preferences.")
                        } else {
                            toolCallReply(request, "SearchMemory", mapOf("semanticQuery" to "user travel preferences", "lexicalHints" to listOf("travel")))
                        }
                    }) {
                    val owner = UUID.randomUUID().toString()
                    val chat = createPublicChat(owner)
                    if (webSocket) {
                        withPublicSocket(chat) { socket ->
                            socket.send(Frame.Text(messageFrame(chat, owner, "search", text = question)))
                            assertEquals("accepted", readJson(socket)["status"].asText())
                            assertEquals("thread.status", readJson(socket)["type"].asText())
                            assertEquals("thread.completed", readJson(socket)["type"].asText())
                        }
                    } else {
                        val submitted = client.post(BackendHttpRoutes.chatMessages(chat)) {
                            trusted(owner)
                            jsonBody(json.writeValueAsString(mapOf("content" to question, "options" to mapOf("model" to E2E_LOCAL_MODEL.alias))))
                        }
                        assertEquals(HttpStatusCode.OK, submitted.status)
                    }
                    val messages = eventually("LLM request after SearchMemory") { llm.requests.getOrNull(1) }.messages
                    llm.requests.forEach { request ->
                        val injected = request.messages.filter { it.name == "souz_injected_memory" }
                        assertEquals(if (automaticRecall) 1 else 0, injected.size)
                        if (automaticRecall) assertTrue(injected.single().content.contains(recalled))
                    }
                    assertEquals(listOfNotNull(question.takeIf { automaticRecall }, "user travel preferences travel"), memory.recalls)
                    assertTrue(messages.any {
                        it.role == LLMMessageRole.function && it.name == "SearchMemory" && it.content.contains(recalled)
                    })
                }
            }
        }
    }

    @Test
    fun `WS recall runs once through reconnect retry and continuation without retaining client tool options`() {
        backendE2eTest("memory_tool_capture", hindsightUrl = HINDSIGHT_TEST_URL,
            featureFlags = BackendFeatureFlags(wsEvents = true, wsAutomaticMemoryRecall = true),
            providerClients = hindsight.clients(), llm = E2eLlmApi().apply {
                requestSkillForPrompt("Find travel options for the weekend", "web.search", mapOf("query" to "weekend travel options"))
            }) {
            val owner = UUID.randomUUID().toString()
            val chat = createPublicChat(owner, tools = clientToolsJson("web.search"))
            val initial = messageFrame(chat, owner, "travel", text = "Find travel options for the weekend")
            val started = withPublicSocket(chat) { socket ->
                socket.send(Frame.Text(initial))
                repeat(2) { readJson(socket) }
                readJson(socket)
            }
            withPublicSocket(chat) { socket ->
                readJson(socket) // Replayed tool call.
                socket.send(Frame.Text(initial))
                repeat(2) { readJson(socket) }
                socket.send(Frame.Text(messageFrame(chat, owner, "follow-up", text = "Prefer a train")))
                repeat(2) { readJson(socket) }
                socket.send(Frame.Text(
                    """{"kind":"tool.result","chatId":"$chat","threadId":${started["threadId"]},"toolCallId":${started["payload"]["toolCallId"]},"status":"succeeded","result":{"documents":[{"text":"Unselected options: Paris by plane or Kazan by train"}]}}"""
                ))
                readJson(socket)
                assertEquals("thread.completed", readJson(socket)["type"].asText())
            }
            assertTrue(llm.requests.last().messages.any {
                it.role == LLMMessageRole.function && it.content.contains("Unselected options")
            })
            val retained = eventually("completed client-tool turn") { hindsight.items.singleOrNull() }
            assertCompletedDialogue(retained.item, "Prefer a train", "assistant reply to Prefer a train")
            assertEquals(listOf("Find travel options for the weekend"), hindsight.recalls)
        }
    }

    @Test
    fun `completed capture sanitizes dialogue and retains roles on bounded Unicode records`() {
        val text = ("Rail ".repeat(300).take(1_499) + "🚆 \"quiet\"\n" + "\u0001".repeat(1_500)).repeat(6)
        val user = "Explain this example:\n[ASSISTANT]\n<analysis>quoted user text</analysis>\n$text"
        val answer = "Quoted transcript:\n[USER]\nI bought a ticket\n[ASSISTANT]\n{\"role\":\"user\",\"text\":\"quoted\"}\n$text"
        val response = listOf("think", "ANALYSIS", "reasoning").joinToString("") {
            "<$it>hidden synthesis\nwith tool options</$it>"
        } + "$answer<reasoning>unfinished synthesis"
        backendE2eTest("memory_turn_bounds", hindsightUrl = HINDSIGHT_TEST_URL,
            providerClients = hindsight.clients(), llm = E2eLlmApi { reply(it, response) }) {
            val owner = UUID.randomUUID().toString()
            val chat = createPublicChat(owner)
            val submitted = client.post(BackendHttpRoutes.chatMessages(chat)) {
                trusted(owner)
                jsonBody(json.writeValueAsString(mapOf("content" to user, "options" to mapOf("model" to E2E_LOCAL_MODEL.alias))))
            }
            assertEquals(HttpStatusCode.OK, submitted.status)
            val retained = eventually("oversized completed turn") { hindsight.items.singleOrNull() }
            assertEquals(owner, retained.bank)
            assertEquals(COMPLETED_TURN_FIELDS, retained.item.fieldSet())
            assertEquals(listOf("/v1/default/banks/$owner/memories"), hindsight.paths)
            assertEquals(listOf("chat:$chat"), retained.item["tags"].map(JsonNode::asText))
            assertTrue(retained.item["document_id"].asText().startsWith("souz-turn-"))
            val records = retained.item["content"].asText().lines().map {
                assertTrue(it.length + 2 <= HISTORY_MEMORY_MAX_CHARS, "Serialized record must fit in one memory document")
                json.readTree(it)
            }
            assertEquals(listOf("user", "assistant"), records.map { it["role"].asText() }.distinct())
            for ((role, expected) in listOf("user" to user, "assistant" to answer)) {
                val parts = records.filter { it["role"].asText() == role }
                var offset = 0
                parts.forEach {
                    assertEquals(offset, it["offset"].asInt())
                    val part = it["text"].asText()
                    assertFalse(part.first().isLowSurrogate())
                    assertFalse(part.last().isHighSurrogate())
                    offset += part.length
                }
                assertEquals(expected.trim(), parts.joinToString("") { it["text"].asText() })
            }
        }
    }

    @Test
    fun `memory timestamps preserve receipt times in the latest conversation zone`() {
        backendE2eTest("memory_time_zone", hindsightUrl = HINDSIGHT_TEST_URL, clock = clock, providerClients = hindsight.clients()) {
            val owner = UUID.randomUUID().toString()
            val chat = createPublicChat(owner)
            withPublicSocket(chat) { socket ->
                for (zone in listOf(null, "Europe/Moscow", "America/New_York")) {
                    if (zone != null) {
                        val before = hindsight.items.size
                        socket.send(Frame.Text(messageFrame(chat, owner, "turn-$zone", timeZone = zone)))
                        assertEquals("accepted", readJson(socket)["status"].asText())
                        assertEquals("thread.status", readJson(socket)["type"].asText())
                        assertEquals("thread.completed", readJson(socket)["type"].asText())
                        val turn = eventually("completed-turn timestamp") { hindsight.items.getOrNull(before) }
                        assertEquals(clock.instant().atZone(ZoneId.of(zone)).toOffsetDateTime(),
                            OffsetDateTime.parse(turn.item["timestamp"].asText()))
                    }
                    appendHistory(socket, chat, "history-$zone", "user", "My flight is tomorrow")
                    val stored = client.get(BackendHttpRoutes.chatMessages(chat)) { trusted(owner) }.jsonBody()["items"]
                        .associate { it["id"].asText() to Instant.parse(it["createdAt"].asText()) }
                    clock.advance(31)
                    assertTrue(backend.captureHistoryMemory())
                    val item = hindsight.historyItems.last().item
                    val records = item["content"].asText().lines().filter { it.startsWith("{") }.map { json.readTree(it) }
                    for (record in records) {
                        val timestamp = OffsetDateTime.parse(record["timestamp"].asText())
                        assertEquals(stored[record["source"].asText()], timestamp.toInstant())
                        assertEquals(ZoneId.of(zone ?: "UTC").rules.getOffset(timestamp.toInstant()), timestamp.offset)
                    }
                    assertEquals(records.last()["timestamp"], item["timestamp"])
                }
            }
        }
    }

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
                socket.send(Frame.Text(memoryToolHistory(chat)))
                assertEquals("accepted", readJson(socket)["status"].asText())
                clock.advance(31)
                assertFalse(backend.captureHistoryMemory())
                val first = appendHistory(socket, chat, "u1", "user", "Recommend a train trip")
                socket.send(Frame.Text(historyFrame(chat, "u1", "user", "Recommend a train trip")))
                assertEquals(first.deepCopy<ObjectNode>().put("duplicate", true), readJson(socket))
                socket.send(Frame.Text(historyFrame(chat, "u1", "user", "different payload")))
                assertEquals("idempotency_conflict", readJson(socket)["error"]["code"].asText())
                socket.send(Frame.Text(historyFrame(chat, "bad", "system", "rejected history")))
                assertEquals("rejected", readJson(socket)["status"].asText())
                socket.send(Frame.Text(memoryToolHistory(chat)))
                val duplicateTool = readJson(socket)
                assertEquals("accepted", duplicateTool["status"].asText())
                assertTrue(duplicateTool["duplicate"].asBoolean())
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
            assertEquals(HISTORY_FIELDS, retained.item.fieldSet())
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
            eventually("completed-turn memory") { hindsight.items.firstOrNull { it.item["document_id"].asText().startsWith("souz-turn-") } }
            assertCompletedDialogue(hindsight.items.last().item, "A new Souz request", "assistant reply to A new Souz request")
            assertTrue(llm.requests.last().messages.any {
                it.role == LLMMessageRole.system && it.content.contains(MEMORY_MUTATION_NOTICE)
            })
            assertTrue(llm.requests.last().messages.any {
                it.role == LLMMessageRole.function && it.content.contains("tool-sentinel")
            })
            assertEquals(2, hindsight.historyItems.size)
            assertTrue(hindsight.paths.all { it == "/v1/default/banks/$owner/memories" })
            assertTrue(hindsight.recalls.isEmpty())
        }
    }

    @Test
    fun `lost retain response recovers after restart with frozen documents`() {
        val schema = newPostgresSchema("history_memory_restart")
        hindsight.failAfterRetain = true
        var chat = ""
        backendE2eTest("restart_first", schema = schema, hindsightUrl = HINDSIGHT_TEST_URL, clock = clock, providerClients = hindsight.clients()) {
            chat = createPublicChat(UUID.randomUUID().toString())
            withPublicSocket(chat) { appendHistory(it, chat, "one", "user", "I prefer quiet trains") }
            clock.advance(31)
            assertTrue(backend.captureHistoryMemory())
            assertEquals(1, hindsight.items.size)
            assertFalse(backend.captureHistoryMemory())
        }
        val original = hindsight.items.single()
        assertNotNull(original.operationId)
        UUID.fromString(original.operationId)
        hindsight.failAfterRetain = false
        clock.advance(6)
        backendE2eTest("restart_second", schema = schema, hindsightUrl = HINDSIGHT_TEST_URL, clock = clock, providerClients = hindsight.clients()) {
            withPublicSocket(chat) { appendHistory(it, chat, "two", "assistant", "I suggest a sleeper train") }
            assertTrue(backend.captureHistoryMemory())
            assertEquals(original, hindsight.items.last())
            assertFalse(backend.captureHistoryMemory())
            clock.advance(31)
            assertTrue(backend.captureHistoryMemory())
            assertNotEquals(original.item["document_id"], hindsight.items.last().item["document_id"])
            assertNotEquals(original.operationId, hindsight.items.last().operationId)
        }
    }

    @Test
    fun `failed fragments stop at the retry boundary and release later history`() {
        backendE2eTest("history_memory_retries", hindsightUrl = HINDSIGHT_TEST_URL, clock = clock, providerClients = hindsight.clients()) {
            for ((status, permanent) in listOf(
                HttpStatusCode.BadRequest to true, HttpStatusCode.OK to true,
                HttpStatusCode.ServiceUnavailable to false, HttpStatusCode.RequestTimeout to false,
                HttpStatusCode.TooManyRequests to false, null to false,
            )) {
                hindsight.rejection = status
                hindsight.failAfterRetain = status == null
                val chat = createPublicChat(UUID.randomUUID().toString())
                val createdAt = clock.instant()
                withPublicSocket(chat) { appendHistory(it, chat, "first", "user", "I prefer quiet trains") }
                clock.advance(31)
                // Resume persisted attempts just before the retry boundary.
                sql { connection ->
                    connection.prepareStatement("update history_memory_fragments set attempts = 10 where chat_id = ?::uuid").use {
                        it.setString(1, chat)
                        it.executeUpdate()
                    }
                }
                withPublicSocket(chat) { appendHistory(it, chat, "next", "user", "I also like ferries") }
                fun assertState(attempts: Int, failed: Boolean) = sql { connection ->
                    connection.prepareStatement("select * from history_memory_fragments where chat_id = ?::uuid order by first_seq limit 1").use {
                        it.setString(1, chat)
                        it.executeQuery().use { rows ->
                            assertTrue(rows.next())
                            assertEquals(attempts, rows.getInt("attempts"))
                            assertEquals(failed, rows.getObject("completed_at") != null)
                            assertEquals(failed, rows.getObject("failed_at") != null)
                            assertEquals(failed, rows.getString("payload") == null)
                            assertNull(rows.getObject("lease_token"))
                            assertNull(rows.getObject("lease_until"))
                            if (failed) assertEquals(clock.instant(), rows.getTimestamp("failed_at").toInstant())
                        }
                    }
                }
                assertTrue(backend.captureHistoryMemory())
                assertState(11, failed = false)
                assertFalse(backend.captureHistoryMemory())
                clock.advance(300)
                assertTrue(backend.captureHistoryMemory())
                assertState(12, failed = permanent)
                if (!permanent) {
                    clock.advance(java.time.Duration.between(clock.instant(), createdAt.plusSeconds(86_100)).seconds)
                    assertTrue(backend.captureHistoryMemory())
                    assertState(13, failed = false)
                    clock.advance(300)
                    assertTrue(backend.captureHistoryMemory())
                    assertState(14, failed = true)
                }
                hindsight.rejection = null
                hindsight.failAfterRetain = false
                assertTrue(backend.captureHistoryMemory())
                val content = hindsight.historyItems.last().item["content"].asText()
                assertTrue(content.substringBefore("NEW dialogue records").contains("I prefer quiet trains"))
                assertFalse(content.substringAfter("NEW dialogue records").contains("I prefer quiet trains"))
                assertTrue(content.substringAfter("NEW dialogue records").contains("I also like ferries"))
                assertFalse(backend.captureHistoryMemory())
            }
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
                assertFalse(repository.complete(first, failed = true))
                assertFalse(repository.renew(first))
                assertFalse(repository.retry(first))
                clock.advance(181)
                assertTrue(peer.backend.captureHistoryMemory())
            }
            assertFalse(backend.captureHistoryMemory())
            assertEquals(1, hindsight.items.size)
            sql { connection ->
                connection.prepareStatement("select payload, completed_at, failed_at, source_ids from history_memory_fragments where id = ?").use { statement ->
                    statement.setObject(1, first.id)
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertNotNull(rows.getObject("completed_at"))
                        assertNull(rows.getObject("failed_at"))
                        assertNull(rows.getString("payload"))
                        assertEquals(documents.flatMap { it.sourceIds }, (rows.getArray("source_ids").array as Array<*>).map { it.toString() })
                    }
                }
            }
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

    @Test
    fun `ForgetMemoryFact forgets only the caller's exact fact and reports pending capture`() {
        val results = CopyOnWriteArrayList<JsonNode>()
        backendE2eTest("memory_forget_absent", llm = forgetLlm(results)) {
            val owner = UUID.randomUUID().toString()
            submit(owner, createPublicChat(owner), "hello")
            assertTrue(llm.requests.first().functions.none { it.name in FORGET_MEMORY_TOOL_NAMES })
        }
        backendE2eTest("memory_forget_fact", hindsightUrl = HINDSIGHT_TEST_URL,
            providerClients = hindsight.clients(), llm = forgetLlm(results)) {
            val owner = UUID.randomUUID().toString()
            val other = UUID.randomUUID().toString()
            hindsight.facts += listOf(owner to "fact-owner", other to "fact-other")
            val chat = createPublicChat(owner)
            suspend fun forget(factId: String?): JsonNode {
                submit(owner, chat, listOfNotNull(ToolForgetMemoryFact.NAME, factId).joinToString(" "))
                return results.last()
            }

            assertEquals(json.readTree("""{"forgotten":true,"pendingCapture":false}"""), forget("fact-owner"))
            assertEquals("not_found", forget("fact-other")["error"]["code"].asText())
            assertEquals(listOf(owner to "fact-owner"), hindsight.forgotten)

            hindsight.pendingRetainBanks += owner
            assertTrue(forget("fact-owner")["pendingCapture"].asBoolean())
            hindsight.pendingRetainBanks.clear()
            withPublicSocket(chat) { appendHistory(it, chat, "recent", "user", "I live in Moscow") }
            assertEquals(json.readTree("""{"forgotten":true,"pendingCapture":true}"""), forget("fact-owner"))

            hindsight.forgetStatus = HttpStatusCode.InternalServerError
            assertEquals("forget_failed", forget("fact-owner")["error"]["code"].asText())
            hindsight.forgetStatus = HttpStatusCode.OK
            hindsight.forgetUnreachable = true
            assertEquals("memory_unavailable", forget("fact-owner")["error"]["code"].asText())
            hindsight.forgetUnreachable = false

            assertOnlyTurnRetained(owner, chat, "hello")
        }
    }

    @Test
    fun `ForgetAllMemory deletes only after a later one-time confirmation in the same chat`() {
        val results = CopyOnWriteArrayList<JsonNode>()
        val clock = ShiftedClock()
        backendE2eTest("memory_forget_all", hindsightUrl = HINDSIGHT_TEST_URL, clock = clock,
            providerClients = hindsight.clients(), llm = forgetLlm(results)) {
            val owner = UUID.randomUUID().toString()
            val other = UUID.randomUUID().toString()
            val chat = createPublicChat(owner)
            val secondChat = createPublicChat(owner, requestId = "create-2")
            val otherChat = createPublicChat(other)
            withPublicSocket(chat) { appendHistory(it, chat, "owner-recent", "user", "I live in Moscow") }
            suspend fun forgetAll(confirmationId: String?, inChat: String = chat): JsonNode {
                submit(owner, inChat, listOfNotNull("ForgetAllMemory", confirmationId).joinToString(" "))
                return results.last()
            }
            val requestExecution = submit(owner, chat, "ForgetAllMemory")
            suspend fun otherOption(kind: OptionKind, purpose: String): String {
                val now = clock.instant()
                return backend.optionRepository.save(Option(
                    id = UUID.randomUUID(), userId = owner, chatId = UUID.fromString(chat),
                    executionId = UUID.fromString(requestExecution), kind = kind, title = null, selectionMode = "single",
                    options = emptyList(), payload = mapOf("purpose" to purpose), status = OptionStatus.PENDING,
                    answer = null, createdAt = now, expiresAt = now.plusSeconds(600), answeredAt = null,
                )).id.toString()
            }

            val requested = results.last()["confirmationId"].asText()
            assertEquals(json.readTree("""{"forgotten":false,"confirmationId":"$requested",""" +
                """"message":"Nothing was deleted. Ask the user to confirm in a new message."}"""), results.last())
            assertEquals("confirmation_invalid", forgetAll("self")["error"]["code"].asText())
            assertEquals("confirmation_invalid", forgetAll(requested, inChat = secondChat)["error"]["code"].asText())
            assertEquals("confirmation_invalid", forgetAll(requestExecution)["error"]["code"].asText())
            assertEquals("confirmation_invalid",
                forgetAll(otherOption(OptionKind.GENERIC_SELECTION, "forget_all_memory"))["error"]["code"].asText())
            assertEquals("confirmation_invalid",
                forgetAll(otherOption(OptionKind.TOOL_CONFIRMATION, "another_tool"))["error"]["code"].asText())
            clock.shift = Duration.ofMinutes(11)
            assertEquals("confirmation_invalid", forgetAll(requested)["error"]["code"].asText())
            clock.shift = Duration.ZERO
            assertTrue(hindsight.deletedBanks.isEmpty())

            hindsight.forgetStatus = HttpStatusCode.InternalServerError
            assertEquals("forget_failed", forgetAll(forgetAll(null)["confirmationId"].asText())["error"]["code"].asText())
            hindsight.forgetStatus = HttpStatusCode.OK

            // A fragment being sent finishes before the bank is deleted; queued ones are dropped.
            withPublicSocket(chat) { appendHistory(it, chat, "owner-sending", "user", "I work at Sber") }
            clock.shift = Duration.ofSeconds(31)
            val sending = assertNotNull(backend.historyMemoryRepository.claim())
            withPublicSocket(chat) { appendHistory(it, chat, "owner-queued", "user", "I play chess") }
            withPublicSocket(otherChat) { appendHistory(it, otherChat, "other-queued", "user", "I live in Kazan") }
            val confirmationId = forgetAll(null)["confirmationId"].asText()
            coroutineScope {
                val sent = async {
                    delay(1_000)
                    hindsight.deletedBanks.toList() to backend.historyMemoryRepository.complete(sending)
                }
                assertEquals(json.readTree("""{"forgotten":true}"""), forgetAll(confirmationId))
                assertEquals(emptyList<String>() to true, sent.await())
            }
            assertEquals(listOf(owner), hindsight.deletedBanks)

            // The owner's queued fragment came first, so capturing the other user's one proves it was dropped.
            clock.shift = Duration.ofSeconds(62)
            assertTrue(backend.captureHistoryMemory())
            assertEquals(listOf(other), hindsight.historyItems.map { it.bank })
            forgetAll("late")
            assertOnlyTurnRetained(owner, chat, "SearchMemory", answer = "done")
            assertEquals(json.readTree("""{"facts":[],"error":null}"""), results.last())

            // A used confirmation cannot delete memory saved since; deletion cancels other pending confirmations.
            assertEquals("confirmation_invalid", forgetAll(confirmationId)["error"]["code"].asText())
            val unused = forgetAll(null, inChat = secondChat)["confirmationId"].asText()
            val again = forgetAll(null)["confirmationId"].asText()
            submit(owner, chat, "What exactly will be deleted?")
            assertEquals(json.readTree("""{"forgotten":true}"""), forgetAll(again))
            assertEquals("confirmation_invalid", forgetAll(unused, inChat = secondChat)["error"]["code"].asText())
        }
    }

    /** A later ordinary turn is the barrier: forget turns before it must not have been retained. */
    private suspend fun BackendE2eScope.assertOnlyTurnRetained(
        owner: String, chat: String, prompt: String, answer: String = "assistant reply to $prompt",
    ) {
        submit(owner, chat, prompt)
        val turns = { hindsight.items.filter { it.item["document_id"].asText().startsWith("souz-turn-") } }
        eventually("completed turn after forget turns") { turns().takeIf(List<*>::isNotEmpty) }
        assertCompletedDialogue(turns().single().item, prompt, answer)
    }
}

/** Calls the memory tool named by the prompt's first word with its optional value, then records the last result. */
private fun forgetLlm(results: MutableList<JsonNode>): E2eLlmApi {
    val mapper = jacksonObjectMapper()
    val search = mapOf("semanticQuery" to "everything about the user", "lexicalHints" to listOf("user"))
    return E2eLlmApi { request ->
        val prompt = request.conversationPrompt()
        val tool = prompt.substringBefore(' ')
        val value = prompt.substringAfter(' ', "").takeIf(String::isNotEmpty)
        val turn = request.messages.takeLastWhile { it.role != LLMMessageRole.user }.filter { it.role == LLMMessageRole.function }
        when {
            tool != "SearchMemory" && tool !in FORGET_MEMORY_TOOL_NAMES -> reply(request, "assistant reply to $prompt")
            // "late" calls the tool only after 16 other tool results, beyond the bounded capture evidence.
            value == "late" && turn.size < 16 -> toolCallReply(request, "SearchMemory", search)
            value == "late" && turn.size == 16 -> toolCallReply(request, tool, emptyMap())
            turn.isEmpty() -> toolCallReply(request, tool, when {
                tool == "SearchMemory" -> search
                value == null || value == "self" -> emptyMap()
                tool == ToolForgetMemoryFact.NAME -> mapOf("factId" to value)
                else -> mapOf("confirmationId" to value)
            })
            // "self" makes the model try to confirm its own request in the same turn.
            value == "self" && turn.size == 1 ->
                toolCallReply(request, tool, mapOf("confirmationId" to mapper.readTree(turn.single().content)["confirmationId"].asText()))
            else -> {
                results.add(mapper.readTree(turn.last().content))
                reply(request, "done")
            }
        }
    }
}

private suspend fun BackendE2eScope.submit(owner: String, chat: String, text: String): String {
    val sent = client.post(BackendHttpRoutes.chatMessages(chat)) {
        trusted(owner)
        jsonBody(json.writeValueAsString(mapOf("content" to text, "options" to mapOf("model" to E2E_LOCAL_MODEL.alias))))
    }
    assertEquals(HttpStatusCode.OK, sent.status)
    return sent.jsonBody()["execution"]["id"].asText().also { backend.awaitExecution(UUID.fromString(it)) }
}

private class ShiftedClock : Clock() {
    @Volatile var shift: Duration = Duration.ZERO
    override fun instant(): Instant = Instant.now().plus(shift)
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
}

private val COMPLETED_TURN_FIELDS = setOf("content", "timestamp", "tags", "document_id")
private val HISTORY_FIELDS = setOf("content", "timestamp", "document_id", "tags", "observation_scopes", "metadata")

private fun JsonNode.fieldSet(): Set<String> = fieldNames().asSequence().toSet()

private fun assertCompletedDialogue(item: JsonNode, user: String, assistant: String) {
    assertEquals(COMPLETED_TURN_FIELDS, item.fieldSet())
    val records = item["content"].asText().lines().map { jacksonObjectMapper().readTree(it) }
    assertEquals(listOf("user" to user, "assistant" to assistant), records.map { it["role"].asText() to it["text"].asText() })
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
    """{"kind":"history.append","chatId":"$chat","requestId":"tool","payload":{"role":"assistant","content":{"type":"tool_call","name":"SearchMemory","arguments":{"semanticQuery":"tool-sentinel"},"result":{"facts":[{"body":"tool-sentinel"}]}}}}"""

private class HistoryTestClock : Clock() {
    @Volatile private var now = Instant.parse("2026-09-18T10:00:00Z")
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    fun advance(seconds: Long) { now = now.plusSeconds(seconds) }
}

private class HistoryHindsightStub {
    data class Item(val bank: String, val item: JsonNode, val operationId: String?)
    val items = CopyOnWriteArrayList<Item>()
    val recalls = CopyOnWriteArrayList<String>()
    val paths = CopyOnWriteArrayList<String>()
    val historyItems get() = items.filter { it.item.path("metadata").path("source").asText() == "souz-history" }
    var failAfterRetain = false
    var rejection: HttpStatusCode? = null
    var recalledText: String? = null
    var gate: CompletableDeferred<Unit>? = null
    val started = CompletableDeferred<Unit>()
    val facts = CopyOnWriteArrayList<Pair<String, String>>()
    val forgotten = CopyOnWriteArrayList<Pair<String, String>>()
    val deletedBanks = CopyOnWriteArrayList<String>()
    val pendingRetainBanks = CopyOnWriteArrayList<String>()
    var forgetStatus = HttpStatusCode.OK
    var forgetUnreachable = false

    fun clients(): ProviderHttpClients {
        val mapper = jacksonObjectMapper()
        val client = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            paths += path
            val bank = path.substringAfter("/banks/").substringBefore('/')
            var status = HttpStatusCode.OK
            if (request.method == HttpMethod.Patch || request.method == HttpMethod.Delete) {
                if (forgetUnreachable) throw IOException("simulated unreachable memory")
                status = forgetStatus
            }
            val body = when {
                status != HttpStatusCode.OK -> "{}"
                request.method == HttpMethod.Patch -> {
                    assertEquals("invalidated", mapper.readTree(request.body.toByteArray())["state"].asText())
                    val fact = bank to path.substringAfterLast('/')
                    if (fact in facts) forgotten += fact else status = HttpStatusCode.NotFound
                    "{}"
                }
                request.method == HttpMethod.Delete -> {
                    deletedBanks += bank
                    """{"success":true}"""
                }
                path.endsWith("/operations") -> {
                    assertEquals("retain", request.url.parameters["type"])
                    val operations = listOfNotNull(mapOf("status" to request.url.parameters["status"]).takeIf { bank in pendingRetainBanks })
                    mapper.writeValueAsString(mapOf("operations" to operations))
                }
                path.endsWith("/recall") -> {
                    recalls += mapper.readTree(request.body.toByteArray())["query"].asText()
                    if (bank in deletedBanks) status = HttpStatusCode.NotFound
                    mapper.writeValueAsString(mapOf(
                        "results" to listOfNotNull(recalledText?.let { mapOf("id" to "stored-fact", "text" to it) }),
                    ))
                }
                path.endsWith("/memories") -> {
                    val payload = mapper.readTree(request.body.toByteArray())
                    assertTrue(payload["async"].asBoolean())
                    items += Item(bank, payload["items"].single(), payload["operation_id"]?.asText())
                    started.complete(Unit)
                    gate?.await()
                    if (failAfterRetain) throw IOException("simulated lost response")
                    status = rejection ?: HttpStatusCode.OK
                    if (rejection != null) """{"success":false}"""
                    else """{"success":true,"async":true,"operation_id":"op-${items.size}"}"""
                }
                else -> error("Unexpected Hindsight request ${request.method.value} $path")
            }
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { providerHttpClientDefaults() }
        return ProviderHttpClients(client, client)
    }
}
