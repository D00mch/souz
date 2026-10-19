package ru.souz.backend.e2e

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.websocket.Frame
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.personality.PERSONALITY_SYNC_COMMAND
import ru.souz.backend.personality.PERSONALITY_VALIDATION_PROMPT
import ru.souz.backend.personality.PersonalityTool
import ru.souz.backend.storage.postgres.newPostgresSchema
import ru.souz.llms.restJsonMapper

class BackendPersonalityE2eTest {
    private val owner = UUID.randomUUID().toString()

    @Test
    fun `style lifecycle is isolated from settings and survives restart`() {
        val schema = newPostgresSchema("personality_lifecycle")
        backendE2eTest("personality_lifecycle", schema = schema, llm = personalityApi()) {
            val chat = createPublicChat(owner)
            patch("""{"systemPrompt":"Base prompt","contextSize":4096}""")
            val cases = listOf("formal" to "formal", "captain" to "captain", "also brief" to "captain; brief",
                "reject" to "captain; brief", "malformed" to "captain; brief", "reset" to null)
            var revision = 1L
            for ((wish, expected) in cases) {
                val rejected = wish in setOf("reject", "malformed")
                turn(chat, wish)
                if (!rejected) revision++
                val stored = settings()
                assertEquals(expected, stored["personalityPrompt"].takeUnless(JsonNode::isNull)?.asText())
                assertEquals(revision, stored["personalityRevision"].asLong())
                assertEquals("Base prompt", stored["systemPrompt"].asText())
                assertEquals(!rejected, toolResult()["saved"].asBoolean())
                val prompt = llm.requests.last { !it.isSummarization }.messages.first().content
                assertEquals(expected != null, "Current user communication style" in prompt)
                if (expected != null) assertTrue(prompt.endsWith(expected))
                patch("""{"temperature":0.2,"streamingMessages":true}""")
                assertEquals(stored["personalityPrompt"], settings()["personalityPrompt"])
            }
            turn(chat, "brief")
            assertTrue(llm.requests.filter { it.messages.first().content == PERSONALITY_VALIDATION_PROMPT }.all {
                it.maxTokens == 4096 && it.messages.none { message -> "Base prompt" in message.content }
            })
            val validationCount = llm.requests.count { it.messages.first().content == PERSONALITY_VALIDATION_PROMPT }
            patch("""{"enabledTools":[]}""")
            turn(chat, "formal")
            assertEquals("skill_not_found", toolResult()["error"]["code"].asText())
            assertEquals(validationCount, llm.requests.count { it.messages.first().content == PERSONALITY_VALIDATION_PROMPT })
            assertEquals("brief", settings()["personalityPrompt"].asText())
            val bootstrap = client.get(BackendHttpRoutes.BOOTSTRAP) { trusted(owner) }.jsonBody()
            assertEquals(settings()["personalityRevision"], bootstrap["settings"]["personalityRevision"])
            assertEquals(1, settings(UUID.randomUUID().toString())["personalityRevision"].asLong())
        }
        backendE2eTest("personality_restart", schema = schema) {
            assertEquals("brief", settings()["personalityPrompt"].asText())
            assertEquals(6, settings()["personalityRevision"].asLong())
        }
    }

    @Test
    fun `long personality leaves room for conversation after reducing the context size`() =
        backendE2eTest("personality_budget", llm = personalityApi()) {
            val chat = createPublicChat(owner)
            val style = "friendly ".repeat(1500).trim()
            patch("""{"contextSize":32768}""")
            turn(chat, style)
            patch("""{"contextSize":16384,"streamingMessages":true}""")
            val before = llm.requests.size
            turn(chat, "also brief")
            val requests = llm.requests.drop(before)
            assertTrue(requests.first().isSummarization, "Budget the existing style before the next conversation call.")
            assertTrue(requests.filter { it.isSummarization }.all { request ->
                request.messages.none { "Current user communication style" in it.content }
            })
            val conversation = requests.last { !it.isSummarization }
            assertTrue(conversation.messages.first().content.endsWith("$style; brief"))
            assertEquals(16384, conversation.maxTokens, "Keep the local model's context window unchanged.")
            assertEquals("$style; brief", settings()["personalityPrompt"].asText())
            assertTrue(toolResult()["saved"].asBoolean())
        }

    @Test
    fun `Orion snapshots prefer the source fall back when offline and reconnect without replay`() =
        backendE2eTest("personality_sync", llm = personalityApi()) {
            val source = createPublicChat(owner)
            val other = createPublicChat(owner, "other")
            withPublicSocket(other) { fallback ->
                acknowledge(fallback, snapshot(fallback), null, 1)
                withPublicSocket(source) { socket ->
                    acknowledge(socket, snapshot(socket), null, 1)
                    coroutineScope {
                        val turn = async { turn(source, "brief") }
                        val first = snapshot(socket)
                        acknowledge(socket, first, "brief", 2, failed = true)
                        val retry = snapshot(socket)
                        assertNotEquals(first["payload"]["toolCallId"], retry["payload"]["toolCallId"])
                        assertEquals(first["threadId"], retry["threadId"])
                        acknowledge(socket, retry, "brief", 2)
                        turn.await()
                        assertEquals("synced", toolResult()["sync"].asText())
                        assertEquals(1, llm.requests.count { it.messages.first().content == PERSONALITY_VALIDATION_PROMPT })
                    }
                }
                coroutineScope {
                    val turn = async { turn(source, "captain") }
                    acknowledge(fallback, snapshot(fallback), "captain", 3)
                    turn.await()
                    assertEquals("synced", toolResult()["sync"].asText())
                }
            }
            turn(source, "reset")
            assertEquals("pending", toolResult()["sync"].asText())
            withPublicSocket(source) { socket -> acknowledge(socket, snapshot(socket), null, 4) }
            for (chat in listOf(source, other)) {
                val events = client.get(BackendHttpRoutes.chatEvents(chat)) { trusted(owner) }.jsonBody()["items"]
                assertTrue(events.none { it.path("payload").path("name").asText() == PERSONALITY_SYNC_COMMAND })
            }
        }

    @Test
    fun `concurrent additions revalidate against the winning revision`() {
        val bothValidating = CompletableDeferred<Unit>()
        var arrivals = 0
        val mutex = Mutex()
        val api = personalityApi { current, wish ->
            if (wish.startsWith("also ") && current.isEmpty()) {
                mutex.withLock { if (++arrivals == 2) bothValidating.complete(Unit) }
                bothValidating.await()
            }
        }
        backendE2eTest("personality_concurrent", llm = api) {
            val chats = listOf(createPublicChat(owner), createPublicChat(owner, "second"))
            coroutineScope { chats.zip(listOf("also brief", "also formal")).map { (chat, wish) -> async { turn(chat, wish) } }.awaitAll() }
            assertEquals(setOf("brief", "formal"), settings()["personalityPrompt"].asText().split("; ").toSet())
            assertEquals(3, settings()["personalityRevision"].asLong())
        }
    }

    private suspend fun BackendE2eScope.patch(body: String) {
        assertEquals(200, client.patch(BackendHttpRoutes.SETTINGS) { trusted(owner); jsonBody(body) }.status.value)
    }
    private suspend fun BackendE2eScope.settings(user: String = owner) =
        client.get(BackendHttpRoutes.SETTINGS) { trusted(user) }.jsonBody()["settings"]
    private fun BackendE2eScope.toolResult() = json.readTree(llm.requests.last { !it.isSummarization }.messages.last { it.name == "RunSkillCommand" }.content)
    private suspend fun BackendE2eScope.turn(chat: String, wish: String) {
        val result = client.post(BackendHttpRoutes.chatMessages(chat)) {
            trusted(owner); jsonBody("""{"content":"$wish","options":{"model":"${E2E_LOCAL_MODEL.alias}"}}""")
        }.jsonBody()
        backend.awaitExecution(UUID.fromString(result["execution"]["id"].asText()))
    }
    private suspend fun BackendE2eScope.snapshot(socket: DefaultClientWebSocketSession): JsonNode = withTimeout(15_000) {
        var frame: JsonNode
        do { frame = readRawJson(socket) } while (frame.path("payload").path("name").asText() != PERSONALITY_SYNC_COMMAND)
        frame
    }
    private suspend fun BackendE2eScope.acknowledge(socket: DefaultClientWebSocketSession, frame: JsonNode, prompt: String?, revision: Long, failed: Boolean = false) {
        assertTrue(frame.has("seq") && frame["seq"].isNull)
        val arguments = frame["payload"]["arguments"]
        assertEquals(setOf("prompt", "revision"), arguments.fieldNames().asSequence().toSet())
        assertEquals(prompt.orEmpty(), arguments["prompt"].asText())
        assertEquals(revision, arguments["revision"].asLong())
        socket.send(Frame.Text(toolResultFrame(frame, if (failed) "failed" else "succeeded",
            if (failed) mapOf("code" to "client_tool_failed", "message" to "retry") else mapOf("outcome" to "applied", "revision" to revision))))
    }
}

private fun personalityApi(before: suspend (String, String) -> Unit = { _, _ -> }) = E2eLlmApi { body ->
    if (body.isSummarization) reply(body, "Earlier conversation summarized.")
    else if (body.messages.first().content == PERSONALITY_VALIDATION_PROMPT) {
        val input = restJsonMapper.readTree(body.messages.last().content)
        val current = input["currentPersonality"].asText()
        val wish = input["request"].asText()
        assertTrue(body.functions.isEmpty())
        assertTrue(body.maxTokens > 0)
        if (input["mode"].asText() == "replace") assertTrue(current.isEmpty())
        before(current, wish)
        reply(body, when (wish) {
            "reject" -> """{"accepted":false,"prompt":"","reason":"Incompatible with voice"}"""
            "malformed" -> """{"accepted":true,"prompt":"bad","reason":""} trailing"""
            else -> restJsonMapper.writeValueAsString(mapOf("accepted" to true, "reason" to "", "prompt" to
                if (wish.startsWith("also ")) listOf(current, wish.removePrefix("also ")).filter(String::isNotEmpty).joinToString("; ") else wish))
        })
    } else {
        val wish = body.conversationPrompt()
        val args = when {
            wish == "reset" -> mapOf("action" to "reset")
            wish.startsWith("also ") -> mapOf("action" to "append", "request" to wish)
            else -> mapOf("request" to wish)
        }
        scriptedSkillReply(body, listOf(SkillScript(PersonalityTool.NAME, args)))
    }
}
