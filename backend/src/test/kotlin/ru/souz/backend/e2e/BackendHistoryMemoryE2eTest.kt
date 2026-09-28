package ru.souz.backend.e2e

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
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
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.websocket.Frame
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
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
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.memory.hindsight.HISTORY_MEMORY_MAX_ATTEMPTS
import ru.souz.backend.memory.hindsight.HISTORY_MEMORY_MAX_CHARS
import ru.souz.backend.memory.hindsight.HISTORY_MEMORY_TRANSIENT_BUDGET_HOURS
import ru.souz.backend.memory.hindsight.HistoryMemoryWorker
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
            val chat = createPublicChat(owner)
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
    fun `memory timestamps use the time zone of the turn and of the latest chat execution`() {
        val zone = ZoneId.of("Asia/Vladivostok")
        backendE2eTest("memory_time_zone", hindsightUrl = HINDSIGHT_TEST_URL, clock = clock, providerClients = hindsight.clients()) {
            val owner = UUID.randomUUID().toString()
            val chat = createPublicChat(owner)
            withPublicSocket(chat) { appendHistory(it, chat, "before", "user", "I moved to Kazan") }
            clock.advance(31)
            assertTrue(backend.captureHistoryMemory())
            assertHistoryTimestamps(hindsight.historyItems.single().item, ZoneOffset.UTC)

            withPublicSocket(chat) { socket ->
                socket.send(Frame.Text(messageFrame(chat, owner, "zone", text = "Remind me tomorrow", timeZone = zone.id)))
                assertEquals("accepted", readJson(socket)["status"].asText())
                assertEquals("thread.status", readJson(socket)["type"].asText())
                assertEquals("thread.completed", readJson(socket)["type"].asText())
                appendHistory(socket, chat, "after", "user", "My flight is tomorrow")
            }
            val turn = eventually("completed-turn memory") {
                hindsight.items.firstOrNull { it.item["document_id"].asText().startsWith("souz-turn-") }
            }
            assertEquals(clock.instant().atZone(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), turn.item["timestamp"].asText())

            clock.advance(31)
            assertTrue(backend.captureHistoryMemory())
            assertHistoryTimestamps(hindsight.historyItems.last().item, ZoneOffset.ofHours(10))
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
                it.role == LLMMessageRole.system && it.content.contains("exact-ID memory deletion is unavailable")
            })
            assertTrue(llm.requests.last().messages.any {
                it.role == LLMMessageRole.function && it.content.contains("tool-sentinel")
            })
            assertEquals(2, hindsight.historyItems.size)
            assertTrue(hindsight.paths.all { it == "/v1/default/banks/$owner/memories" })
            assertEquals(3, hindsight.items.size)
            assertTrue(hindsight.items.all { it.async })
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
        }
    }

    @Test
    fun `permanently rejected fragment fails after the attempt limit without blocking its chat or other chats`() {
        backendE2eTest("history_memory_exhausted", hindsightUrl = HINDSIGHT_TEST_URL, clock = clock, providerClients = hindsight.clients()) {
            hindsight.reject = { if (it.newRecordsContain("unextractable")) HttpStatusCode.BadRequest else null }
            val failing = createPublicChat(UUID.randomUUID().toString())
            val healthy = createPublicChat(UUID.randomUUID().toString())
            withPublicSocket(failing) { appendHistory(it, failing, "stuck", "user", "An unextractable message") }
            clock.advance(31)
            assertTrue(backend.captureHistoryMemory())
            withPublicSocket(failing) { appendHistory(it, failing, "next", "user", "The next message in this chat") }
            withPublicSocket(healthy) { appendHistory(it, healthy, "other", "user", "A message in another chat") }
            // Remaining attempts of the stuck fragment, then the other chat and the next fragment of the stuck chat.
            repeat(HISTORY_MEMORY_MAX_ATTEMPTS + 1) {
                clock.advance(301)
                assertTrue(backend.captureHistoryMemory())
            }
            assertFalse(backend.captureHistoryMemory())

            assertEquals(HISTORY_MEMORY_MAX_ATTEMPTS, hindsight.rejected.size)
            val outcomes = hindsight.outcomes.toList()
            assertTrue(outcomes.indexOf("chat:$healthy" to true) < outcomes.lastIndexOf("chat:$failing" to false))
            assertEquals("chat:$failing" to true, outcomes.last())
            assertTrue(hindsight.historyItems.last().item["content"].asText().contains("The next message in this chat"))
            assertEquals(
                listOf(Triple(HISTORY_MEMORY_MAX_ATTEMPTS, true, true), Triple(1, true, false)),
                fragmentStates(failing),
            )
        }
    }

    @Test
    fun `fragments retrying at rollout follow the same limits`() {
        backendE2eTest("history_memory_legacy_attempts", hindsightUrl = HINDSIGHT_TEST_URL, clock = clock, providerClients = hindsight.clients()) {
            val permanent = createPublicChat(UUID.randomUUID().toString())
            val transient = createPublicChat(UUID.randomUUID().toString())
            hindsight.reject = { item ->
                when (item["tags"].single().asText()) {
                    "chat:$permanent" -> HttpStatusCode.BadRequest.takeIf { item.newRecordsContain("unextractable") }
                    else -> HttpStatusCode.ServiceUnavailable
                }
            }
            withPublicSocket(permanent) { appendHistory(it, permanent, "stuck", "user", "An unextractable message") }
            withPublicSocket(transient) { appendHistory(it, transient, "unavailable", "user", "I prefer aisle seats") }
            clock.advance(31)
            sql { connection -> connection.createStatement().use { it.executeUpdate("update history_memory_fragments set attempts = 20") } }
            withPublicSocket(permanent) { appendHistory(it, permanent, "next", "user", "The next message in this chat") }
            clock.advance(301)

            repeat(2) { assertTrue(backend.captureHistoryMemory()) }
            assertEquals(2, hindsight.rejected.size)
            assertEquals(listOf(Triple(21, true, true), Triple(0, false, false)), fragmentStates(permanent))
            assertEquals(listOf(Triple(21, false, false)), fragmentStates(transient))
            assertTrue(backend.captureHistoryMemory())
            assertTrue(hindsight.historyItems.single().item["content"].asText().contains("The next message in this chat"))
        }
    }

    @Test
    fun `transient failures retry past the attempt limit until the time budget`() {
        backendE2eTest("history_memory_transient", hindsightUrl = HINDSIGHT_TEST_URL, clock = clock, providerClients = hindsight.clients()) {
            val statuses = listOf(HttpStatusCode.ServiceUnavailable, HttpStatusCode.RequestTimeout, HttpStatusCode.TooManyRequests)
            val chats = statuses.associateBy { createPublicChat(UUID.randomUUID().toString()) }
            hindsight.reject = { item -> chats.entries.single { (chat, _) -> item["tags"].single().asText() == "chat:$chat" }.value }
            chats.keys.forEach { chat -> withPublicSocket(chat) { appendHistory(it, chat, "unavailable", "user", "I prefer aisle seats") } }
            clock.advance(31)
            val logs = withWorkerLogs {
                repeat(chats.size * (HISTORY_MEMORY_MAX_ATTEMPTS + 1)) {
                    assertTrue(backend.captureHistoryMemory())
                    clock.advance(301)
                }
                chats.keys.forEach { chat ->
                    val (attempts, completed, failed) = fragmentStates(chat).single()
                    assertTrue(attempts > HISTORY_MEMORY_MAX_ATTEMPTS && !completed && !failed, "$attempts $completed $failed")
                }
                clock.advance(HISTORY_MEMORY_TRANSIENT_BUDGET_HOURS * 3_600L)
                repeat(chats.size) { assertTrue(backend.captureHistoryMemory()) }
                assertFalse(backend.captureHistoryMemory())
            }
            chats.keys.forEach { chat ->
                val (_, completed, failed) = fragmentStates(chat).single()
                assertTrue(completed && failed)
            }
            assertEquals(
                statuses.map { "http_${it.value}" }.toSet(),
                logs.filter { it.level == Level.ERROR }.map {
                    assertTrue(it.formattedMessage.contains("reason=time budget"), it.formattedMessage)
                    it.formattedMessage.substringAfter("category=")
                }.toSet(),
            )
        }
    }

    @Test
    fun `retain rejected with success false fails the fragment after the attempt limit`() {
        backendE2eTest("history_memory_not_accepted", hindsightUrl = HINDSIGHT_TEST_URL, clock = clock, providerClients = hindsight.clients()) {
            hindsight.reject = { HttpStatusCode.OK }
            val chat = createPublicChat(UUID.randomUUID().toString())
            withPublicSocket(chat) { appendHistory(it, chat, "rejected", "user", "I prefer aisle seats") }
            clock.advance(31)
            val logs = withWorkerLogs {
                repeat(HISTORY_MEMORY_MAX_ATTEMPTS) {
                    assertTrue(backend.captureHistoryMemory())
                    clock.advance(301)
                }
            }
            assertEquals(listOf(Triple(HISTORY_MEMORY_MAX_ATTEMPTS, true, true)), fragmentStates(chat))
            assertEquals(HISTORY_MEMORY_MAX_ATTEMPTS, hindsight.rejected.size)
            val failure = logs.single { it.level == Level.ERROR }.formattedMessage
            assertTrue(failure.contains("reason=attempts") && failure.contains("category=HindsightRetainRejected"), failure)
        }
    }

    @Test
    fun `worker that lost its lease does not record a final failure`() {
        backendE2eTest("history_memory_lost_final", hindsightUrl = HINDSIGHT_TEST_URL, clock = clock, providerClients = hindsight.clients()) {
            hindsight.reject = { HttpStatusCode.BadRequest }
            val chat = createPublicChat(UUID.randomUUID().toString())
            withPublicSocket(chat) { appendHistory(it, chat, "stuck", "user", "An unextractable message") }
            clock.advance(31)
            sql { connection ->
                connection.prepareStatement("update history_memory_fragments set attempts = ? where chat_id = ?::uuid").use {
                    it.setInt(1, HISTORY_MEMORY_MAX_ATTEMPTS - 1)
                    it.setString(2, chat)
                    it.executeUpdate()
                }
            }
            hindsight.gate = CompletableDeferred()
            val logs = withWorkerLogs {
                coroutineScope {
                    val capturing = async { backend.captureHistoryMemory() }
                    withTimeout(5_000) { hindsight.started.await() }
                    clock.advance(181)
                    assertNotNull(backend.historyMemoryRepository.claim())
                    hindsight.gate!!.complete(Unit)
                    assertTrue(capturing.await())
                }
            }
            assertEquals(listOf(Triple(HISTORY_MEMORY_MAX_ATTEMPTS + 1, false, false)), fragmentStates(chat))
            assertTrue(logs.none { it.level == Level.ERROR })
            assertTrue(logs.any { it.level == Level.WARN && it.formattedMessage.startsWith("History memory lease lost") })
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
            sql { connection ->
                connection.prepareStatement("select payload, completed_at, source_ids from history_memory_fragments where id = ?").use { statement ->
                    statement.setObject(1, first.id)
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertNotNull(rows.getObject("completed_at"))
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
}

private val COMPLETED_TURN_FIELDS = setOf("content", "timestamp", "tags", "document_id")
private val HISTORY_FIELDS = setOf("content", "timestamp", "document_id", "tags", "observation_scopes", "metadata")

private fun JsonNode.fieldSet(): Set<String> = fieldNames().asSequence().toSet()

/** Matches only a history document's own records, not the preceding dialogue it carries as context. */
private fun JsonNode.newRecordsContain(text: String): Boolean =
    this["content"].asText().substringAfter("NEW dialogue records").contains(text)

private suspend fun withWorkerLogs(block: suspend () -> Unit): List<ILoggingEvent> {
    val logs = ConcurrentLinkedQueue<ILoggingEvent>()
    val logger = LoggerFactory.getLogger(HistoryMemoryWorker::class.java) as Logger
    val appender = object : AppenderBase<ILoggingEvent>() {
        override fun append(event: ILoggingEvent) { logs.add(event) }
    }.apply { start() }
    logger.addAppender(appender)
    try { block() } finally { logger.detachAppender(appender); appender.stop() }
    return logs.toList()
}

/** Attempts, completion and failure of the chat's fragments in queue order. */
private fun BackendE2eScope.fragmentStates(chat: String): List<Triple<Int, Boolean, Boolean>> = sql { connection ->
    connection.prepareStatement(
        "select attempts, completed_at is not null as completed, failed_at is not null as failed " +
            "from history_memory_fragments where chat_id = ?::uuid order by first_seq",
    ).use { statement ->
        statement.setString(1, chat)
        statement.executeQuery().use { rows ->
            buildList { while (rows.next()) add(Triple(rows.getInt("attempts"), rows.getBoolean("completed"), rows.getBoolean("failed"))) }
        }
    }
}

private fun assertHistoryTimestamps(item: JsonNode, offset: ZoneOffset) {
    val records = item["content"].asText().lines().filter { it.startsWith("{") }.map { jacksonObjectMapper().readTree(it) }
    assertTrue(records.isNotEmpty())
    (records.map { it["timestamp"].asText() } + item["timestamp"].asText()).forEach {
        assertEquals(offset, OffsetDateTime.parse(it).offset, it)
    }
}

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
    data class Item(val bank: String, val item: JsonNode, val async: Boolean)
    val items = CopyOnWriteArrayList<Item>()
    val rejected = CopyOnWriteArrayList<Item>()
    /** Retain outcomes in request order: the item's chat tag and whether it was accepted. */
    val outcomes = CopyOnWriteArrayList<Pair<String, Boolean>>()
    val recalls = CopyOnWriteArrayList<String>()
    val paths = CopyOnWriteArrayList<String>()
    val historyItems get() = items.filter { it.item.path("metadata").path("source").asText() == "souz-history" }
    /** Status for a rejected retain item: an HTTP error, or [HttpStatusCode.OK] for `success: false`; `null` accepts it. */
    var reject: (JsonNode) -> HttpStatusCode? = { null }
    var failAfterRetain = false
    var recalledText: String? = null
    var gate: CompletableDeferred<Unit>? = null
    val started = CompletableDeferred<Unit>()

    fun clients(): ProviderHttpClients {
        val mapper = jacksonObjectMapper()
        val client = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            paths += path
            val bank = path.substringAfter("/banks/").substringBefore('/')
            var status = HttpStatusCode.OK
            val body = when {
                path.endsWith("/recall") -> {
                    recalls += mapper.readTree(request.body.toByteArray())["query"].asText()
                    mapper.writeValueAsString(mapOf(
                        "results" to listOfNotNull(recalledText?.let { mapOf("id" to "stored-fact", "text" to it) }),
                    ))
                }
                path.endsWith("/memories") -> {
                    val payload = mapper.readTree(request.body.toByteArray())
                    val item = Item(bank, payload["items"].single(), payload.path("async").asBoolean())
                    val chat = item.item.path("tags").joinToString(",", transform = JsonNode::asText)
                    started.complete(Unit)
                    gate?.await()
                    val rejection = reject(item.item)
                    if (rejection != null) {
                        rejected += item
                        outcomes += chat to false
                        status = rejection
                        if (rejection == HttpStatusCode.OK) """{"success":false}""" else """{"detail":"extraction unavailable"}"""
                    } else {
                        items += item
                        outcomes += chat to true
                        if (failAfterRetain) throw IOException("simulated lost response")
                        if (item.async) """{"success":true,"async":true,"operation_id":"operation-${items.size}"}"""
                        else """{"success":true,"async":false}"""
                    }
                }
                else -> error("Unexpected Hindsight request ${request.method.value} $path")
            }
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { providerHttpClientDefaults() }
        return ProviderHttpClients(client, client)
    }
}
