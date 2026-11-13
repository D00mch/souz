package ru.souz.backend.e2e

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import ru.souz.backend.storage.postgres.newPostgresSchema
import ru.souz.llms.restJsonMapper

class BackendPersonalityE2eTest {
    private val owner = UUID.randomUUID().toString()

    @Test
    fun `style lifecycle is isolated from settings and survives restart`() {
        val schema = newPostgresSchema("personality_lifecycle")
        backendE2eTest("personality_lifecycle", schema = schema, llm = personalityApi()) {
            val chat = createPublicChat(owner)
            patch("""{"systemPrompt":"Base prompt"}""")
            val cases = listOf("formal" to "formal", "captain" to "captain", "also brief" to "captain; brief",
                "reject" to "captain; brief", "malformed" to "captain; brief", "reset" to null)
            var revision = 1L
            for ((wish, expected) in cases) {
                val rejected = wish == "reject" || wish == "malformed"
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
    fun `Orion snapshots prefer the source fall back when offline and reconnect without replay`() =
        backendE2eTest("personality_sync", llm = personalityApi()) {
            val source = createPublicChat(owner, tools = clientToolsJson(PERSONALITY_SYNC_COMMAND))
            val other = createPublicChat(owner, "other", clientToolsJson(PERSONALITY_SYNC_COMMAND))
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
            assertEquals("skipped", toolResult()["sync"].asText())
            withPublicSocket(source) { socket -> acknowledge(socket, snapshot(socket), null, 4) }
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
                acknowledge(device, snapshot(device), null, 1)
                withPublicSocket(source) { bot ->
                    coroutineScope {
                        val changing = async { turn(source, "brief") }
                        acknowledge(device, snapshot(device), "brief", 2)
                        changing.await()
                    }
                    assertEquals("synced", toolResult()["sync"].asText())
                    assertFalse(PERSONALITY_SYNC_COMMAND in llm.requests.last().messages.first().content)
                    // Read raw frames: the common reader acknowledges personality commands automatically.
                    bot.send(Frame.Text(historyFrame(source, "barrier", "user", "passive context")))
                    assertEquals("ack", readRawJson(bot)["kind"].asText())
                }
            }
        }

    @Test
    fun `unknown support saves immediately and discovered support synchronizes on reconnect`() {
        val schema = newPostgresSchema("personality_discovery")
        var source = ""
        backendE2eTest("personality_discovery_seed", schema = schema, llm = personalityApi()) {
            source = createPublicChat(owner)
            withPublicSocket(source) { socket ->
                socket.send(Frame.Text(historyFrame(source, "ready", "user", "Connection ready")))
                assertEquals("ack", readRawJson(socket)["kind"].asText())
                turn(source, "brief")
                assertTrue(toolResult()["saved"].asBoolean())
                assertEquals("skipped", toolResult()["sync"].asText())
                assertEquals(2, settings()["personalityRevision"].asLong())
                assertEquals("brief", settings()["personalityPrompt"].asText())
                assertEquals(1, llm.requests.count { it.messages.first().content == PERSONALITY_VALIDATION_PROMPT })
                discover(socket, source, clientToolsJson(PERSONALITY_SYNC_COMMAND))
                assertEquals(0, toolResult()["tools"].size())
                // Discovery does not resend the saved style or consume another revision.
                socket.send(Frame.Text(historyFrame(source, "after-discovery", "user", "Still connected")))
                assertEquals("ack", readRawJson(socket)["kind"].asText())
            }
        }
        backendE2eTest("personality_discovery_restart", schema = schema, llm = personalityApi()) {
            withPublicSocket(source) { socket ->
                // A saved discovery is sufficient for reconnect synchronization after restart.
                acknowledge(socket, snapshot(socket), "brief", 2)
                coroutineScope {
                    val changing = async { turn(source, "captain") }
                    acknowledge(socket, snapshot(socket), "captain", 3)
                    changing.await()
                }
                assertEquals("synced", toolResult()["sync"].asText())
                assertEquals(3, settings()["personalityRevision"].asLong())
            }
        }
    }

    @Test
    fun `completed empty catalogue saves without a receiver and does not rediscover`() =
        backendE2eTest("personality_empty_catalogue", llm = personalityApi()) {
            val source = createPublicChat(owner)
            withPublicSocket(source) { socket ->
                socket.send(Frame.Text(historyFrame(source, "ready", "user", "Connection ready")))
                assertEquals("ack", readRawJson(socket)["kind"].asText())
                discover(socket, source, "[]")
                for ((index, style) in listOf("brief", "captain").withIndex()) {
                    turn(source, style)
                    assertTrue(toolResult()["saved"].asBoolean())
                    assertEquals("skipped", toolResult()["sync"].asText())
                    assertEquals(index + 2L, settings()["personalityRevision"].asLong())
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

    @Test
    fun `account personality initialization requires the authenticated owner over HTTP and WebSocket`() =
        backendE2eTest("personality_creation_auth") {
            val fields = mapOf("voice" to "sber", "appeal" to "official", "style" to "formal", "personality" to "Injected instructions")
            withMultiChatSocket { socket ->
                for ((key, value) in fields) {
                    val payload = mapOf("userId" to owner, "parameters" to mapOf(key to value))
                    val response = client.post(BackendHttpRoutes.CHATS) {
                        jsonBody(json.writeValueAsString(payload + mapOf("requestId" to key, "clientType" to "backend")))
                    }
                    assertEquals(401, response.status.value)
                    assertEquals("untrusted_proxy", response.jsonBody()["error"]["code"].asText())
                    socket.send(Frame.Text(json.writeValueAsString(mapOf("kind" to "chat.create", "requestId" to key, "payload" to payload))))
                    val ack = readRawJson(socket)
                    assertEquals("rejected", ack["status"].asText())
                    assertEquals("untrusted_proxy", ack["error"]["code"].asText())
                }
            }
            val headers = listOf(
                mapOf("X-User-Id" to owner),
                mapOf("X-Souz-Proxy-Auth" to E2E_PROXY_TOKEN),
                mapOf("X-User-Id" to owner, "X-Souz-Proxy-Auth" to "forged"),
            )
            for (values in headers) {
                val rejected = client.post(BackendHttpRoutes.CHATS) {
                    values.forEach { (key, value) -> header(key, value) }
                    jsonBody(json.writeValueAsString(mapOf("userId" to owner, "requestId" to "forged", "clientType" to "backend", "parameters" to fields)))
                }
                assertEquals(401, rejected.status.value)
                // The same middleware rejects these headers before WebSocket upgrade.
                assertEquals(401, client.get("${BackendHttpRoutes.WS}?clientType=backend") {
                    values.forEach { (key, value) -> header(key, value) }
                }.status.value)
            }
            val attacker = UUID.randomUUID().toString()
            val rejected = client.post(BackendHttpRoutes.CHATS) {
                trusted(attacker)
                jsonBody(json.writeValueAsString(mapOf("userId" to owner, "requestId" to "mismatch", "clientType" to "backend", "parameters" to fields)))
            }
            assertEquals(403, rejected.status.value)
            assertEquals("user_identity_mismatch", rejected.jsonBody()["error"]["code"].asText())
            withMultiChatSocket(attacker) { socket ->
                socket.send(Frame.Text(json.writeValueAsString(mapOf("kind" to "chat.create", "requestId" to "mismatch", "payload" to mapOf("userId" to owner, "parameters" to fields)))))
                val ack = readRawJson(socket)
                assertEquals("rejected", ack["status"].asText())
                assertEquals("user_identity_mismatch", ack["error"]["code"].asText())
            }
            assertEquals(json.readTree("""{"voice":"joy","appeal":"no_official","style":"informal","personality":""}"""), settings()["personalityParameters"])
            assertEquals(0, client.get(BackendHttpRoutes.CHATS) { trusted(owner) }.jsonBody()["items"].size())
            val accepted = client.post(BackendHttpRoutes.CHATS) {
                trusted(owner)
                jsonBody(json.writeValueAsString(mapOf("userId" to owner, "requestId" to "personality", "clientType" to "backend", "parameters" to fields)))
            }
            assertEquals(201, accepted.status.value)
            assertEquals(json.valueToTree<JsonNode>(fields), settings()["personalityParameters"])
            val retryPayload = mapOf("userId" to owner, "parameters" to fields)
            assertEquals(401, client.post(BackendHttpRoutes.CHATS) {
                jsonBody(json.writeValueAsString(retryPayload + mapOf("requestId" to "personality", "clientType" to "backend")))
            }.status.value)
            withMultiChatSocket { socket ->
                socket.send(Frame.Text(json.writeValueAsString(mapOf("kind" to "chat.create", "requestId" to "personality", "payload" to retryPayload))))
                val ack = readRawJson(socket)
                assertEquals("rejected", ack["status"].asText())
                assertEquals("untrusted_proxy", ack["error"]["code"].asText())
            }
            assertEquals(json.valueToTree<JsonNode>(fields), settings()["personalityParameters"])
        }

    @Test
    fun `credential-free creation preserves activation words without claiming first personality initialization`() =
        backendE2eTest("personality_creation_public") {
            val tools = json.readTree(clientToolsJson(PERSONALITY_SYNC_COMMAND))
            val created = client.post(BackendHttpRoutes.CHATS) {
                jsonBody(json.writeValueAsString(mapOf("userId" to owner, "requestId" to "public-http", "clientType" to "backend", "tools" to tools)))
            }
            assertEquals(201, created.status.value)
            val httpChat = created.jsonBody()["chat"]["id"].asText()
            val initial = mapOf("voice" to "sber", "appeal" to "official", "style" to "neutral", "personality" to "Owner instructions")
            val publicChat = withMultiChatSocket { socket ->
                val payload = mapOf("userId" to owner, "tools" to tools, "parameters" to mapOf("your_name" to "  Луна ", "future" to "a"))
                socket.send(Frame.Text(json.writeValueAsString(mapOf("kind" to "chat.create", "requestId" to "public-ws", "payload" to payload))))
                val ack = readRawJson(socket)
                assertEquals("accepted", ack["status"].asText())
                val defaults = snapshot(socket)
                assertEquals("  Луна ", defaults["payload"]["arguments"]["parameters"]["your_name"].asText())
                acknowledge(socket, defaults, null, 1)
                val response = client.post(BackendHttpRoutes.CHATS) {
                    trusted(owner)
                    jsonBody(json.writeValueAsString(mapOf("userId" to owner, "requestId" to "owner", "clientType" to "backend", "parameters" to initial)))
                }
                assertEquals(201, response.status.value)
                assertEquals(json.valueToTree<JsonNode>(initial), settings()["personalityParameters"])
                ack["chatId"].asText()
            }
            withPublicSocket(publicChat) { socket ->
                val frame = snapshot(socket)
                assertEquals(json.valueToTree<JsonNode>(initial + ("your_name" to "  Луна ")), frame["payload"]["arguments"]["parameters"])
                // This receiver already applied defaults at revision 1 before the owner authenticated.
                acknowledge(socket, frame, "Owner instructions", 2)
            }
            withPublicSocket(httpChat) { socket ->
                val frame = snapshot(socket)
                assertEquals(json.valueToTree<JsonNode>(initial), frame["payload"]["arguments"]["parameters"])
                acknowledge(socket, frame, "Owner instructions", 2)
            }
        }

    @Test
    fun `HTTP and WebSocket creation share user preferences but each receiver keeps its own activation word`() =
        backendE2eTest("personality_creation", llm = personalityApi()) {
            val initial = mapOf("voice" to "sber", "appeal" to "official", "style" to "neutral", "personality" to "")
            val tools = json.readTree(clientToolsJson(PERSONALITY_SYNC_COMMAND))
            val payload = mapOf("userId" to owner, "parameters" to initial + mapOf("your_name" to "  Луна ", "future" to "a"), "tools" to tools)
            suspend fun create(id: String, body: Map<String, Any>) = client.post(BackendHttpRoutes.CHATS) {
                trusted(owner)
                jsonBody(json.writeValueAsString(body + mapOf("requestId" to id, "clientType" to "backend")))
            }
            patch("""{"temperature":0.2}""")
            withMultiChatSocket(owner) { socket ->
                socket.send(Frame.Text(json.writeValueAsString(mapOf("kind" to "chat.create", "requestId" to "first", "payload" to payload))))
                val ack = readRawJson(socket)
                assertEquals("accepted", ack["status"].asText())
                val frame = snapshot(socket)
                assertEquals(json.valueToTree<JsonNode>(initial + ("your_name" to "  Луна ")), frame["payload"]["arguments"]["parameters"])
                acknowledge(socket, frame, null, 2)
                val retry = create("first", payload)
                assertEquals(200, retry.status.value)
                assertEquals(ack["chatId"], retry.jsonBody()["chat"]["id"])
            }
            assertEquals(409, create("first", payload + ("parameters" to initial + mapOf("your_name" to "  Луна ", "future" to "b"))).status.value)
            for (invalid in listOf(json.nullNode(), mapOf("voice" to "sberkot"), mapOf("extra" to 1), mapOf("extra" to null))) {
                assertEquals(400, create("invalid", payload + mapOf("parameters" to invalid)).status.value)
            }
            val second = create("second", payload + ("parameters" to mapOf("voice" to "joy", "your_name" to "")))
            assertEquals(201, second.status.value)
            val id = second.jsonBody()["chat"]["id"].asText()
            withPublicSocket(id) { socket ->
                val frame = snapshot(socket)
                assertEquals(json.valueToTree<JsonNode>(initial + ("your_name" to "")), frame["payload"]["arguments"]["parameters"])
                acknowledge(socket, frame, null, 2)
                coroutineScope {
                    val updating = async { turn(id, "formal") }
                    val updated = snapshot(socket)
                    val expected = mapOf("voice" to "afina", "appeal" to "official", "style" to "formal", "personality" to "formal")
                    assertEquals(json.valueToTree<JsonNode>(expected + ("your_name" to "")), updated["payload"]["arguments"]["parameters"])
                    acknowledge(socket, updated, "formal", 3)
                    updating.await()
                    assertEquals(json.valueToTree<JsonNode>(expected), settings()["personalityParameters"])
                }
            }
            turn(id, "voice only")
            assertEquals("", settings()["personalityParameters"]["personality"].asText())
            assertEquals("afina", settings()["personalityParameters"]["voice"].asText())
            turn(id, "reset")
            assertEquals(json.readTree("""{"voice":"joy","appeal":"no_official","style":"informal","personality":""}"""), settings()["personalityParameters"])
        }

    private suspend fun BackendE2eScope.discover(socket: DefaultClientWebSocketSession, chat: String, tools: String) =
        withTimeout(15_000) {
            coroutineScope {
                val discovering = async { turn(chat, "discover:$chat") }
                var frame: JsonNode
                do { frame = readRawJson(socket) } while (frame.path("payload").path("name").asText() != "orion.tools.list")
                socket.send(Frame.Text(toolResultFrame(frame, "succeeded", mapOf("tools" to json.readTree(tools)))))
                discovering.await()
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
        do {
            frame = readRawJson(socket)
            if (frame.path("type").asText() == "tool.call.started") {
                assertEquals(PERSONALITY_SYNC_COMMAND, frame["payload"]["name"].asText())
            }
        } while (frame.path("payload").path("name").asText() != PERSONALITY_SYNC_COMMAND)
        frame
    }
    private suspend fun BackendE2eScope.acknowledge(socket: DefaultClientWebSocketSession, frame: JsonNode, prompt: String?, revision: Long, failed: Boolean = false) {
        assertTrue(frame.has("seq") && frame["seq"].isNull)
        val arguments = frame["payload"]["arguments"]
        assertEquals(setOf("parameters", "revision"), arguments.fieldNames().asSequence().toSet())
        assertEquals(prompt.orEmpty(), arguments["parameters"]["personality"].asText())
        assertEquals(revision, arguments["revision"].asLong())
        socket.send(Frame.Text(toolResultFrame(frame, if (failed) "failed" else "succeeded",
            if (failed) mapOf("code" to "client_tool_failed", "message" to "retry") else mapOf("outcome" to "applied", "revision" to revision))))
    }
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
