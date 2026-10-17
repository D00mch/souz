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
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.personality.PersonalityTool
import ru.souz.backend.personality.VALIDATION_PROMPT
import ru.souz.backend.storage.postgres.newPostgresSchema
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.restJsonMapper

class BackendPersonalityE2eTest {
    private val owner = UUID.randomUUID().toString()

    @Test fun `replace append rejection and reset affect this turn and synchronize without replay`() =
        backendE2eTest("personality", llm = personalityApi(), featureFlags = BackendFeatureFlags(wsEvents = true, streamingMessages = true)) {
            patch("""{"systemPrompt":"BASE","streamingMessages":true,"contextSize":4096}""")
            val chat = createPublicChat(owner)
            withPublicSocket(chat) { socket ->
                acknowledge(socket, snapshot(socket), "", 1)
                var revision = 1L
                for ((wish, expected) in listOf("formal" to "formal", "brief" to "brief", "also formal" to "brief; formal",
                    "reject" to "brief; formal", "malformed" to "brief; formal", "reset" to null)) {
                    val accepted = wish !in setOf("reject", "malformed")
                    if (accepted) revision++
                    socket.send(Frame.Text(messageFrame(chat, owner, UUID.randomUUID().toString(), text = wish)))
                    withTimeout(15_000) {
                        var thread = ""
                        while (true) {
                            val frame = readRawJson(socket)
                            if (frame.has("thread")) thread = frame["thread"]["id"].asText()
                            when (frame.path("type").asText()) {
                                "tool.call.started" -> if (frame.path("payload").path("name").asText() == "prompt.personality.set") {
                                    assertTrue(accepted)
                                    assertEquals(thread, frame["threadId"].asText())
                                    acknowledge(socket, frame, expected.orEmpty(), revision)
                                }
                                "thread.completed" -> break
                                "thread.failed" -> error(frame.toString())
                            }
                        }
                    }
                    assertEquals(accepted, toolResult()["saved"].asBoolean())
                    assertEquals(expected, settings()["personalityPrompt"].takeUnless { it.isNull }?.asText())
                    assertEquals(revision, settings()["personalityRevision"].asLong())
                    val system = llm.requests.last().messages.first().content
                    assertTrue(llm.requests.last().stream)
                    assertTrue(if (expected == null) !system.contains("User communication style") else system.endsWith("\n$expected"))
                    patch("""{"temperature":0.3}""")
                    assertEquals("BASE", settings()["systemPrompt"].asText())
                    assertEquals(revision, settings()["personalityRevision"].asLong())
                }
            }
            assertEquals(setOf(4096), llm.requests.filter { it.messages.first().content == VALIDATION_PROMPT }.map { it.maxTokens }.toSet())
            withPublicSocket(chat) { acknowledge(it, snapshot(it), "", 5) }
            assertFalse(client.get(BackendHttpRoutes.chatEvents(chat)) { trusted(owner) }.jsonBody().toString().contains("prompt.personality.set"))
        }

    @Test fun `offline state survives restart and multi chat subscriptions remain owner scoped`() {
        val schema = newPostgresSchema("personality_restart")
        var chat = ""
        backendE2eTest("personality_restart", schema = schema, llm = personalityApi()) {
            chat = createPublicChat(owner)
            turn(chat, "formal")
            assertEquals("pending", toolResult()["sync"].asText())
            patch("""{"enabledTools":[]}""")
            turn(chat, "brief")
            assertEquals("formal", settings()["personalityPrompt"].asText())
            assertEquals(1, llm.requests.count { it.messages.first().content == VALIDATION_PROMPT })
        }
        backendE2eTest("personality_restart", schema = schema) {
            withMultiChatSocket { socket ->
                for (kind in listOf("chat.subscribe", "chat.unsubscribe", "chat.subscribe")) {
                    socket.send(Frame.Text("""{"kind":"$kind","chatId":"$chat","requestId":"$kind"}"""))
                    assertEquals("accepted", readJson(socket)["status"].asText())
                    if (kind == "chat.subscribe") {
                        acknowledge(socket, snapshot(socket), "formal", 2)
                        assertEquals("accepted", readRawJson(socket)["status"].asText())
                    }
                }
                socket.send(Frame.Text("""{"kind":"chat.create","requestId":"other","payload":{"userId":"${UUID.randomUUID()}"}}"""))
                assertEquals("accepted", readRawJson(socket)["status"].asText())
                acknowledge(socket, snapshot(socket), "", 1)
            }
        }
    }

    @Test fun `concurrent normalization retries preserve replacement versus addition intent`() {
        for (wish in listOf("formal", "also formal")) {
            val arrived = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            backendE2eTest("personality_race", llm = personalityApi { current, request ->
                if (request == wish && current.isEmpty()) { arrived.complete(Unit); release.await() }
            }) {
                val first = createPublicChat(owner)
                val second = createPublicChat(owner, "second")
                coroutineScope {
                    val pending = async { turn(first, wish) }
                    arrived.await()
                    turn(second, "brief")
                    release.complete(Unit)
                    pending.await()
                }
                assertEquals(if (wish == "formal") "formal" else "brief; formal", settings()["personalityPrompt"].asText())
                assertEquals(3, settings()["personalityRevision"].asInt())
            }
        }
    }

    @Test fun `HTTP change selects another live channel and retries the committed snapshot`() =
        backendE2eTest("personality_retry", llm = personalityApi()) {
            val source = createPublicChat(owner)
            val target = createPublicChat(owner, "target")
            withPublicSocket(target) { socket ->
                acknowledge(socket, snapshot(socket), "", 1)
                coroutineScope {
                    val pending = async { turn(source, "brief") }
                    val first = snapshot(socket)
                    assertEquals(target, first["chatId"].asText())
                    acknowledge(socket, first, "brief", 2, failed = true)
                    val retry = snapshot(socket)
                    assertEquals(first["threadId"], retry["threadId"])
                    assertNotEquals(first["payload"]["toolCallId"], retry["payload"]["toolCallId"])
                    acknowledge(socket, retry, "brief", 2)
                    pending.await()
                    assertEquals("synced", toolResult()["sync"].asText())
                    assertEquals(1, llm.requests.count { it.messages.first().content == VALIDATION_PROMPT })
                }
            }
        }

    private suspend fun BackendE2eScope.patch(body: String) {
        assertEquals(200, client.patch(BackendHttpRoutes.SETTINGS) { trusted(owner); jsonBody(body) }.status.value)
    }
    private suspend fun BackendE2eScope.settings() = client.get(BackendHttpRoutes.SETTINGS) { trusted(owner) }.jsonBody()["settings"]
    private fun BackendE2eScope.toolResult() = json.readTree(llm.requests.last().messages.last { it.name == "RunSkillCommand" }.content)
    private suspend fun BackendE2eScope.turn(chat: String, wish: String) {
        val result = client.post(BackendHttpRoutes.chatMessages(chat)) {
            trusted(owner); jsonBody("""{"content":"$wish","options":{"model":"${E2E_LOCAL_MODEL.alias}"}}""")
        }
        backend.awaitExecution(UUID.fromString(result.jsonBody()["execution"]["id"].asText()))
    }
    private suspend fun BackendE2eScope.snapshot(socket: DefaultClientWebSocketSession): JsonNode = withTimeout(15_000) {
        var frame: JsonNode
        do { frame = readRawJson(socket) } while (frame.path("payload").path("name").asText() != "prompt.personality.set")
        frame
    }
    private suspend fun BackendE2eScope.acknowledge(socket: DefaultClientWebSocketSession, frame: JsonNode, prompt: String, revision: Long, failed: Boolean = false) {
        assertTrue(frame.has("seq") && frame["seq"].isNull)
        assertTrue(frame["threadId"].asText().isNotBlank())
        val arguments = frame["payload"]["arguments"]
        assertEquals(setOf("prompt", "revision"), arguments.fieldNames().asSequence().toSet())
        assertEquals(prompt, arguments["prompt"].asText())
        assertEquals(revision, arguments["revision"].asLong())
        val outcome = if (failed) mapOf("error" to mapOf("code" to "client_tool_timed_out", "message" to "unknown"))
            else mapOf("result" to mapOf("outcome" to "applied", "revision" to revision))
        socket.send(Frame.Text(json.writeValueAsString(mapOf(
            "kind" to "tool.result", "chatId" to frame["chatId"].asText(),
            "threadId" to frame["threadId"].asText(), "toolCallId" to frame["payload"]["toolCallId"].asText(),
            "status" to if (failed) "timed_out" else "succeeded",
        ) + outcome)))
    }
}

private fun personalityApi(before: suspend (String, String) -> Unit = { _, _ -> }) = E2eLlmApi { body ->
    if (body.messages.first().content == VALIDATION_PROMPT) {
        val input = restJsonMapper.readTree(body.messages.last().content)
        val current = input["currentPersonality"].asText()
        val wish = input["request"].asText()
        if (input["mode"].asText() == "replace") assertEquals("", current)
        assertTrue(body.functions.isEmpty())
        before(current, wish)
        val output = when (wish) {
            "reject" -> """{"accepted":false,"prompt":"","reason":"Voice incompatible"}"""
            "malformed" -> """{"accepted":true,"prompt":"bad","reason":""} trailing text"""
            else -> restJsonMapper.writeValueAsString(mapOf("accepted" to true, "reason" to "", "prompt" to
                if (wish.startsWith("also ")) listOf(current, wish.removePrefix("also ")).filter(String::isNotEmpty).joinToString("; ") else wish))
        }
        reply(body, "```json\n$output\n```")
    } else {
        val turn = body.messages.drop(body.messages.indexOfLast { it.role == LLMMessageRole.user })
        val wish = body.conversationPrompt()
        when {
            turn.any { it.name == "RunSkillCommand" } -> reply(body, "Done")
            turn.any { it.name == "GetSkillByName" } -> toolCallReply(body, "RunSkillCommand", mapOf("skillId" to PersonalityTool.NAME,
                "arguments" to if (wish == "reset") mapOf("action" to "reset")
                else mapOf("action" to if (wish.startsWith("also ")) "append" else "update", "request" to wish)))
            else -> toolCallReply(body, "GetSkillByName", mapOf("skillId" to PersonalityTool.NAME))
        }
    }
}
