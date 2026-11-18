package ru.souz.backend.e2e

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.websocket.Frame
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
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
import ru.souz.backend.settings.model.PersonalityParameters
import ru.souz.backend.storage.postgres.newPostgresSchema
import ru.souz.llms.restJsonMapper

class BackendPersonalityE2eTest {
    private val owner = UUID.randomUUID().toString()
    private val defaults = PersonalityParameters()
    private val afina = PersonalityParameters(voice = "afina", appeal = "official", style = "formal")

    @Test
    fun `style lifecycle is isolated from settings and survives restart`() {
        val schema = newPostgresSchema("personality_lifecycle")
        backendE2eTest("personality_lifecycle", schema = schema, llm = personalityApi()) {
            val chat = createPublicChat(owner)
            patch("""{"systemPrompt":"Base prompt"}""")
            val appended = afina.copy(personality = "captain; brief")
            val cases = listOf(
                "formal" to afina.copy(personality = "formal"),
                "captain" to afina.copy(personality = "captain"),
                "also brief" to appended,
                "reject" to appended,
                "malformed" to appended,
                "voice only" to afina,
                "reset" to defaults,
            )
            val personalityFields = listOf("personalityParameters", "personalityPrompt", "personalityRevision")
            var revision = 1L
            for ((wish, parameters) in cases) {
                val rejected = wish == "reject" || wish == "malformed"
                turn(chat, wish)
                if (!rejected) revision++
                val stored = settings()
                val expected = parameters.personality.ifEmpty { null }
                assertEquals(json.valueToTree<JsonNode>(parameters), stored["personalityParameters"])
                assertEquals(expected, stored["personalityPrompt"].takeUnless(JsonNode::isNull)?.asText())
                assertEquals(revision, stored["personalityRevision"].asLong())
                assertEquals("Base prompt", stored["systemPrompt"].asText())
                assertEquals(!rejected, toolResult()["saved"].asBoolean())
                val prompt = llm.requests.last { !it.isSummarization }.messages.first().content
                assertTrue("voice=${parameters.voice}" in prompt)
                assertEquals(expected != null, "Current user communication style" in prompt)
                if (expected != null) assertTrue(prompt.endsWith(expected))
                val patched = patch("""{"temperature":0.2,"streamingMessages":true}""")
                for (field in personalityFields) assertEquals(stored[field], patched[field])
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
            val stored = settings()
            assertEquals("brief", stored["personalityPrompt"].asText())
            val bootstrap = client.get(BackendHttpRoutes.BOOTSTRAP) { trusted(owner) }.jsonBody()
            for (field in personalityFields) assertEquals(stored[field], bootstrap["settings"][field])
            assertEquals(1, settings(UUID.randomUUID().toString())["personalityRevision"].asLong())
        }
        backendE2eTest("personality_restart", schema = schema) {
            assertEquals("brief", settings()["personalityPrompt"].asText())
            assertEquals(7, settings()["personalityRevision"].asLong())
        }
    }

    @Test
    fun `personality uses the server context window independently of legacy user overrides`() =
        backendE2eTest("personality_budget", llm = personalityApi(), settingsSource = object : ru.souz.backend.config.BackendConfigSource {
            override fun env(key: String): String? = if (key == "CONTEXT_SIZE") "32000" else null
            override fun property(key: String): String? = null
        }) {
            val chat = createPublicChat(owner)
            val style = "friendly ".repeat(1500).trim()
            turn(chat, style)
            patch("""{"contextSize":1,"streamingMessages":true}""")
            assertFalse(settings().has("contextSize"))
            val before = llm.requests.size
            turn(chat, "also brief")
            val requests = llm.requests.drop(before)
            assertTrue(requests.filter { it.isSummarization }.all { request ->
                request.messages.none { "Current user communication style" in it.content }
            })
            val conversation = requests.last { !it.isSummarization }
            assertTrue(conversation.messages.first().content.endsWith("$style; brief"))
            assertEquals(32000, conversation.contextSize)
            assertEquals(4096, conversation.maxTokens)
            assertEquals("$style; brief", settings()["personalityPrompt"].asText())
            assertTrue(toolResult()["saved"].asBoolean())
        }

    @Test
    fun `chat creation initializes Orion snapshots with receiver activation words through retry fallback and reconnect`() =
        backendE2eTest("personality_sync", llm = personalityApi()) {
            val initial = PersonalityParameters(voice = "sber", appeal = "no_official", style = "neutral")
            val activationWord = "  Луна "
            val parameters = initial.wireParameters(activationWord) + ("future" to "a")
            val payload = mapOf("userId" to owner, "parameters" to parameters, "tools" to json.readTree(clientToolsJson(PERSONALITY_SYNC_COMMAND)))
            suspend fun create(id: String, body: Map<String, Any>) = client.post(BackendHttpRoutes.CHATS) {
                jsonBody(json.writeValueAsString(body + mapOf("requestId" to id, "clientType" to "backend")))
            }
            patch("""{"temperature":0.2}""")
            val source = withMultiChatSocket { socket ->
                socket.send(Frame.Text(json.writeValueAsString(mapOf("kind" to "chat.create", "requestId" to "first", "payload" to payload))))
                val ack = readRawJson(socket)
                assertEquals("accepted", ack["status"].asText())
                acknowledge(socket, snapshot(socket), initial, 1, yourName = activationWord)
                val retry = create("first", payload)
                assertEquals(200, retry.status.value)
                assertEquals(ack["chatId"], retry.jsonBody()["chat"]["id"])
                ack["chatId"].asText()
            }
            assertEquals(409, create("first", payload + ("parameters" to parameters + ("future" to "b"))).status.value)
            for (invalid in listOf(json.nullNode(), mapOf("voice" to "sberkot"), mapOf("extra" to 1), mapOf("extra" to null))) {
                assertEquals(400, create("invalid", payload + ("parameters" to invalid)).status.value)
            }
            val second = create("second", payload + ("parameters" to mapOf("voice" to "joy", "your_name" to "")))
            assertEquals(201, second.status.value)
            val other = second.jsonBody()["chat"]["id"].asText()
            assertEquals(json.valueToTree<JsonNode>(initial.wireParameters()), settings()["personalityParameters"])
            withPublicSocket(other) { fallback ->
                acknowledge(fallback, snapshot(fallback), initial, 1, yourName = "")
                withPublicSocket(source) { socket ->
                    acknowledge(socket, snapshot(socket), initial, 1, yourName = activationWord)
                    coroutineScope {
                        val turn = async { turn(source, "brief") }
                        val updated = afina.copy(personality = "brief")
                        val first = snapshot(socket)
                        acknowledge(socket, first, updated, 2, yourName = activationWord, failed = true)
                        val retry = snapshot(socket)
                        assertNotEquals(first["payload"]["toolCallId"], retry["payload"]["toolCallId"])
                        assertEquals(first["threadId"], retry["threadId"])
                        acknowledge(socket, retry, updated, 2, yourName = activationWord)
                        turn.await()
                        assertEquals("synced", toolResult()["sync"].asText())
                        assertEquals(1, llm.requests.count { it.messages.first().content == PERSONALITY_VALIDATION_PROMPT })
                    }
                }
                updateAndAcknowledge(fallback, source, "captain", 3, yourName = "")
            }
            turn(source, "reset")
            assertEquals("skipped", toolResult()["sync"].asText())
            withPublicSocket(source) { acknowledge(it, snapshot(it), defaults, 4, yourName = activationWord) }
            for (chat in listOf(source, other)) {
                val events = client.get(BackendHttpRoutes.chatEvents(chat)) { trusted(owner) }.jsonBody()["items"]
                assertTrue(events.none { it.path("payload").path("name").asText() == PERSONALITY_SYNC_COMMAND })
            }
        }

    @Test
    fun `an undeclared source uses a declaring receiver without broadcasting or exposing the control`() =
        backendE2eTest("personality_declared", llm = personalityApi()) {
            val source = createPublicChat(owner)
            val provider = createPublicChat(owner, "orion", clientToolsJson(PERSONALITY_SYNC_COMMAND))
            withPublicSocket(provider) { device ->
                acknowledge(device, snapshot(device), defaults, 1)
                withPublicSocket(source) { bot ->
                    updateAndAcknowledge(device, source, "brief", 2)
                    assertFalse(PERSONALITY_SYNC_COMMAND in llm.requests.last().messages.first().content)
                    assertNoCommands(bot, source, "barrier")
                }
            }
        }

    @Test
    fun `unknown and empty catalogues save immediately and discovered support synchronizes after restart`() {
        val schema = newPostgresSchema("personality_discovery")
        var source = ""
        backendE2eTest("personality_discovery_seed", schema = schema, llm = personalityApi()) {
            source = createPublicChat(owner)
            withPublicSocket(source) { socket ->
                assertNoCommands(socket, source, "ready")
                for ((index, style) in listOf("brief", "captain").withIndex()) {
                    if (index == 1) discover(socket, source, "[]")
                    turn(source, style)
                    assertTrue(toolResult()["saved"].asBoolean())
                    assertEquals("skipped", toolResult()["sync"].asText())
                    val stored = settings()
                    assertEquals(index + 2L, stored["personalityRevision"].asLong())
                    assertEquals(style, stored["personalityPrompt"].asText())
                    assertNoCommands(socket, source, "saved-$index")
                }
                discover(socket, source, clientToolsJson(PERSONALITY_SYNC_COMMAND))
                assertNoCommands(socket, source, "after-discovery")
            }
        }
        backendE2eTest("personality_discovery_restart", schema = schema, llm = personalityApi()) {
            withPublicSocket(source) { socket ->
                acknowledge(socket, snapshot(socket), afina.copy(personality = "captain"), 3)
                updateAndAcknowledge(socket, source, "formal", 4)
                assertEquals(4, settings()["personalityRevision"].asLong())
            }
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

    private suspend fun BackendE2eScope.assertNoCommands(socket: DefaultClientWebSocketSession, chat: String, requestId: String) =
        withTimeout(15_000) {
            socket.send(Frame.Text(historyFrame(chat, requestId, "user", "Connection ready")))
            var frame: JsonNode
            do {
                frame = readRawJson(socket)
                assertNotEquals("tool.call.started", frame.path("type").asText())
            } while (frame.path("requestId").asText() != requestId)
            assertEquals("accepted", frame["status"].asText())
        }

    private suspend fun BackendE2eScope.discover(socket: DefaultClientWebSocketSession, chat: String, tools: String) =
        withTimeout(15_000) {
            coroutineScope {
                val discovering = async { turn(chat, "discover:$chat") }
                var frame: JsonNode
                do { frame = readRawJson(socket) } while (frame.path("payload").path("name").asText() != "orion.tools.list")
                socket.send(Frame.Text(toolResultFrame(frame, "succeeded", mapOf("tools" to json.readTree(tools)))))
                discovering.await()
                assertEquals(json.createArrayNode(), toolResult()["tools"])
            }
        }

    private suspend fun BackendE2eScope.patch(body: String) =
        client.patch(BackendHttpRoutes.SETTINGS) { trusted(owner); jsonBody(body) }
            .also { assertEquals(200, it.status.value) }.jsonBody()["settings"]
    private suspend fun BackendE2eScope.settings(user: String = owner) =
        client.get(BackendHttpRoutes.SETTINGS) { trusted(user) }.jsonBody()["settings"]
    private fun BackendE2eScope.toolResult() = json.readTree(llm.requests.last { !it.isSummarization }.messages.last { it.name == "RunSkillCommand" }.content)
    private suspend fun BackendE2eScope.turn(chat: String, wish: String) {
        val result = client.post(BackendHttpRoutes.chatMessages(chat)) {
            trusted(owner); jsonBody("""{"content":"$wish","options":{"model":"${E2E_MODEL.alias}"}}""")
        }.jsonBody()
        backend.awaitExecution(UUID.fromString(result["execution"]["id"].asText()))
    }
    private suspend fun BackendE2eScope.snapshot(socket: DefaultClientWebSocketSession): JsonNode = withTimeout(15_000) {
        var frame: JsonNode
        do {
            frame = readRawJson(socket)
            if (frame.path("type").asText() == "tool.call.started") {
                assertEquals(PERSONALITY_SYNC_COMMAND, frame["payload"]["name"].asText())
            }
        } while (frame.path("payload").path("name").asText() != PERSONALITY_SYNC_COMMAND)
        frame
    }
    private suspend fun BackendE2eScope.updateAndAcknowledge(
        socket: DefaultClientWebSocketSession, chat: String, wish: String, revision: Long, yourName: String? = null,
    ) = coroutineScope {
        val changing = async { turn(chat, wish) }
        acknowledge(socket, snapshot(socket), afina.copy(personality = wish), revision, yourName)
        changing.await()
        assertEquals("synced", toolResult()["sync"].asText())
    }

    private suspend fun BackendE2eScope.acknowledge(
        socket: DefaultClientWebSocketSession, frame: JsonNode, parameters: PersonalityParameters, revision: Long,
        yourName: String? = null, failed: Boolean = false,
    ) {
        assertTrue(frame.has("seq") && frame["seq"].isNull)
        val arguments = frame["payload"]["arguments"]
        assertEquals(setOf("parameters", "revision"), arguments.fieldNames().asSequence().toSet())
        assertEquals(json.valueToTree<JsonNode>(parameters.wireParameters(yourName)), arguments["parameters"])
        assertEquals(revision, arguments["revision"].asLong())
        socket.send(Frame.Text(toolResultFrame(frame, if (failed) "failed" else "succeeded",
            if (failed) mapOf("code" to "client_tool_failed", "message" to "retry") else mapOf("outcome" to "applied", "revision" to revision))))
    }

    private fun PersonalityParameters.wireParameters(yourName: String? = null) =
        mapOf("voice" to voice, "appeal" to appeal, "style" to style, "personality" to personality) +
            listOfNotNull(yourName?.let { "your_name" to it })
}

private fun personalityApi(before: suspend (String, String) -> Unit = { _, _ -> }) = E2eLlmApi { body ->
    if (body.isSummarization) reply(body, "Earlier conversation summarized.")
    else if (body.messages.first().content == PERSONALITY_VALIDATION_PROMPT) {
        val input = restJsonMapper.readTree(body.messages.last().content)
        val current = input["current"]["personality"].asText()
        val wish = input["request"].asText()
        assertTrue(body.functions.isEmpty())
        assertTrue(body.maxTokens > 0)
        if (input["mode"].asText() == "replace") assertTrue(current.isEmpty())
        before(current, wish)
        reply(body, when (wish) {
            "reject" -> """{"accepted":false,"parameters":{},"reason":"Incompatible with voice"}"""
            "malformed" -> """{"accepted":true,"prompt":"bad","reason":""} trailing"""
            else -> restJsonMapper.writeValueAsString(mapOf("accepted" to true, "reason" to "", "parameters" to mapOf(
                "voice" to "afina", "appeal" to "official", "style" to "formal", "personality" to
                when {
                    wish == "voice only" -> ""
                    wish.startsWith("also ") -> listOf(current, wish.removePrefix("also ")).filter(String::isNotEmpty).joinToString("; ")
                    else -> wish
                })))
        })
    } else {
        val wish = body.conversationPrompt()
        val args = when {
            wish == "reset" -> mapOf("action" to "reset")
            wish.startsWith("also ") -> mapOf("action" to "append", "request" to wish)
            else -> mapOf("request" to wish)
        }
        val script = if (wish.startsWith("discover:"))
            SkillScript("orion.tools.list", mapOf("channelId" to wish.substringAfter(':')))
        else SkillScript(PersonalityTool.NAME, args)
        scriptedSkillReply(body, listOf(script))
    }
}
