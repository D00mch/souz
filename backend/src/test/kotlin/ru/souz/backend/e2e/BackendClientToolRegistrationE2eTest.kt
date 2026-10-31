package ru.souz.backend.e2e

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import java.util.UUID
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.storage.postgres.newPostgresSchema
import ru.souz.backend.client.ClientToolDefinition
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMMessageRole
import ru.souz.backend.personality.PERSONALITY_SYNC_COMMAND

class BackendClientToolRegistrationE2eTest {
    private val definition = """{"name":"speaker.say","description":"Speak text","parameters":{"type":"object","properties":{"text":{"type":"string"},"volume":{"type":"integer"}},"required":["text","volume"],"additionalProperties":false}}"""

    @Test
    fun `server controls stay hidden after declaration and discovery without hiding ordinary client commands`() {
        var provider = ""
        val ordinary = "prompt.personality.preview"
        val llm = E2eLlmApi { request ->
            val turn = request.messages.drop(request.messages.indexOfLast { it.role == LLMMessageRole.user })
            if (turn.any { it.role == LLMMessageRole.function }) reply(request, "Done")
            else when (request.conversationPrompt().substringBefore(':')) {
                "lookup" -> toolCallReply(request, "GetSkillByName", mapOf("skillId" to PERSONALITY_SYNC_COMMAND))
                "invoke" -> toolCallReply(request, "RunSkillCommand", mapOf("skillId" to PERSONALITY_SYNC_COMMAND,
                    "arguments" to mapOf("channelId" to provider, "prompt" to "bad", "revision" to 1)))
                "delegate" -> toolCallReply(request, "SpawnSubagent", mapOf("task" to "Change personality",
                    "skillIds" to listOf(PERSONALITY_SYNC_COMMAND)))
                else -> toolCallReply(request, "RunSkillCommand", mapOf(
                    "skillId" to if (request.conversationPrompt() == "discover") "orion.tools.list" else ordinary,
                    "arguments" to mapOf("channelId" to provider)))
            }
        }
        backendE2eTest("e2e_control_visibility", llm = llm) {
            val owner = UUID.randomUUID().toString()
            val source = createPublicChat(owner)
            val tools = clientToolsJson(PERSONALITY_SYNC_COMMAND, ordinary)
            provider = createPublicChat(owner, "provider", tools)
            withMultiChatSocket { socket ->
                subscribe(socket, provider)
                for (state in listOf("declared", "discovered")) {
                    if (state == "discovered") {
                        assertEquals("thread.completed", answer(socket, submit(socket, source, owner, "discover"),
                            """{"tools":$tools}""")["type"].asText())
                        assertEquals(listOf(ordinary), output()["tools"].map { it["name"].asText() })
                    }
                    for (operation in listOf("lookup", "invoke", "delegate")) {
                        assertEquals("thread.completed", submit(socket, source, owner, "$operation:$state")["type"].asText())
                        val request = llm.requests.last()
                        assertFalse(PERSONALITY_SYNC_COMMAND in request.messages.first().content)
                        val result = request.messages.last { it.role == LLMMessageRole.function }
                        assertEquals("skill_not_found", json.readTree(result.content)["error"]["code"].asText())
                    }
                    val call = submit(socket, source, owner, "ordinary:$state")
                    assertEquals(ordinary, call["payload"]["name"].asText())
                    assertEquals("thread.completed", answer(socket, call)["type"].asText())
                }
            }
        }
    }

    @Test
    fun `only discovery is injected and declared tools retain their schemas guidance and deadlines`() =
        backendE2eTest("e2e_declared_tools") {
            val owner = UUID.randomUUID().toString()
            val parameters = LLMRequest.Parameters("object", mapOf("query" to LLMRequest.Property("string")),
                required = listOf("query"), additionalProperties = false)
            val tools = json.writeValueAsString(listOf(ClientToolDefinition("device.media.open", "Custom media description", parameters)))
            val chat = createPublicChat(owner, tools = tools)
            withMultiChatSocket { socket ->
                for ((name, arguments, seconds) in listOf(
                    Triple("device.media.open", mapOf("query" to "The Thing"), 60L),
                    Triple("orion.tools.list", emptyMap(), 30L),
                )) {
                    llm.requestSkillForPrompt(name, name, arguments)
                    val before = Instant.now()
                    val started = submit(socket, chat, owner, name)
                    val after = Instant.now()
                    val payload = started["payload"]
                    assertEquals(name, payload["name"].asText())
                    assertEquals(json.valueToTree<JsonNode>(arguments), payload["arguments"])
                    val deadline = Instant.parse(payload["deadlineAt"].asText())
                    assertTrue(deadline >= before.plusSeconds(seconds) && deadline <= after.plusSeconds(seconds))
                    val detail = json.readTree(llm.requests.last().messages.last { it.name == "GetSkillByName" }.content)["skill"]
                    assertTrue("RunSkillCommand" in detail["description"].asText())
                    if (name == "device.media.open") {
                        assertTrue(detail["description"].asText().startsWith("Custom media description\n\n"))
                        assertTrue("# Open media" in detail["description"].asText())
                        assertTrue("Omit routing `channelId`" in detail["description"].asText())
                        assertFalse("active client device" in detail["description"].asText())
                        assertEquals(json.valueToTree<JsonNode>(parameters), detail["inputSchema"])
                    }
                    assertEquals("thread.completed", answer(socket, started,
                        if (name == "orion.tools.list") """{"tools":[]}""" else """{"ok":true}""")["type"].asText())
                }
                llm.requestSkillForPrompt("invalid schema", "device.media.open", mapOf("text" to "wrong schema"))
                assertEquals("thread.completed", submit(socket, chat, owner, "invalid schema")["type"].asText())
                assertEquals("mcp_invalid_arguments", output()["error"]["code"].asText())
            }
            val otherOwner = UUID.randomUUID().toString()
            val empty = createPublicChat(otherOwner)
            withMultiChatSocket { socket ->
                for (name in listOf("device.media.open", "user.ask", "web.search", "orion.call")) {
                    llm.requestSkillForPrompt(name, name, emptyMap())
                    assertEquals("thread.completed", submit(socket, empty, otherOwner, name)["type"].asText())
                    assertEquals("skill_not_found", output()["error"]["code"].asText())
                    assertTrue("orion.tools.list" in llm.requests.last().messages.first().content)
                }
                llm.requestSkillForPrompt("empty discovery", "orion.tools.list", emptyMap())
                val started = submit(socket, empty, otherOwner, "empty discovery")
                assertEquals("thread.completed", answer(socket, started, """{"tools":[]}""")["type"].asText())
                llm.requestSkillForPrompt("discover questions", "orion.tools.list", emptyMap(),
                    "user.ask" to mapOf("question" to "Continue?"))
                val discovery = submit(socket, empty, otherOwner, "discover questions")
                val before = Instant.now()
                val question = answer(socket, discovery, """{"tools":${clientToolsJson("user.ask")}}""")
                val after = Instant.now()
                assertEquals("user.ask", question["payload"]["name"].asText())
                assertEquals("Continue?", question["payload"]["arguments"]["question"].asText())
                val deadline = Instant.parse(question["payload"]["deadlineAt"].asText())
                assertTrue(deadline >= before.plusSeconds(300) && deadline <= after.plusSeconds(300))
                assertEquals("thread.completed", answer(socket, question, """{"answer":"Yes"}""")["type"].asText())
            }
        }

    @Test
    fun `discovery belongs to its provider and is shared with empty chats without overriding other providers`() =
        backendE2eTest("e2e_discovery_refresh") {
            val owner = UUID.randomUUID().toString()
            val source = createPublicChat(owner)
            val first = createPublicChat(owner, "first", tools = "[$definition]")
            val second = createPublicChat(owner, "second")
            val clock = """{"name":"current_time","description":"${"Current time ".repeat(750)}","parameters":{"type":"object","additionalProperties":false}}"""
            val speaker = definition.replace("Speak text", "Discovered speaker")
            val speech = mapOf<String, Any>("text" to "hello", "volume" to 1)
            withMultiChatSocket { socket ->
                subscribe(socket, first, second)
                llm.requestSkillForPrompt("remote discovery", "orion.tools.list", mapOf("channelId" to first), "speaker.say" to (speech + ("channelId" to first)))
                val remote = submit(socket, source, owner, "remote discovery")
                assertEquals(first, remote["chatId"].asText())
                assertEquals(json.readTree("{}"), remote["payload"]["arguments"])
                val spoken = answer(socket, remote, """{"tools":[$clock,$speaker]}""")
                assertTrue(output().has("knowledgeId"))
                assertEquals(first, spoken["chatId"].asText())
                assertTrue(spoken["seq"].isNull)
                assertEquals(json.valueToTree<JsonNode>(speech), spoken["payload"]["arguments"])
                assertEquals("thread.completed", answer(socket, spoken)["type"].asText())
                for ((failure, result) in listOf(
                    "strict" to """{"tools":[],"unknown":true}""",
                    "self" to """{"tools":[${clock.replace("current_time", "orion.tools.list")}]}""",
                    "transport" to "{}",
                    "storage" to """{"tools":[]}""",
                )) {
                    val prompt = "$failure refresh"
                    llm.requestSkillForPrompt(prompt, "orion.tools.list", mapOf("channelId" to first), "current_time" to mapOf("channelId" to first))
                    val refresh = submit(socket, source, owner, prompt)
                    if (failure == "storage") backend.sql { connection ->
                        connection.createStatement().use { it.execute(
                            "alter table chats add constraint reject_discovery check (discovered_client_tools->>'channelId' <> '$first') not valid") }
                    }
                    val retained = try {
                        answer(socket, refresh, result, errorCode = "client_tool_failed".takeIf { failure == "transport" })
                    } finally {
                        if (failure == "storage") backend.sql { connection ->
                            connection.createStatement().use { it.execute("alter table chats drop constraint reject_discovery") }
                        }
                    }
                    assertEquals(if (failure == "strict" || failure == "self") "invalid_client_tool_catalogue" else "client_tool_failed",
                        output()["error"]["code"].asText())
                    assertEquals("current_time", retained["payload"]["name"].asText())
                    assertEquals(first, retained["chatId"].asText())
                    assertEquals("thread.completed", answer(socket, retained)["type"].asText())
                }
                // Clearing another channel cannot clear the first channel's discovery.
                llm.requestSkillForPrompt("clear other", "orion.tools.list", mapOf("channelId" to second), "current_time" to mapOf("channelId" to first))
                val retained = answer(socket, submit(socket, source, owner, "clear other"), """{"tools":[]}""")
                assertEquals(first, retained["chatId"].asText())
                assertEquals("thread.completed", answer(socket, retained)["type"].asText())
                llm.requestSkillForPrompt("wrong provider", "current_time", mapOf("channelId" to second))
                assertEquals("thread.completed", submit(socket, source, owner, "wrong provider")["type"].asText())
                assertEquals("client_tool_unavailable", output()["error"]["code"].asText())
                llm.requestSkillForPrompt("clear provider", "orion.tools.list", mapOf("channelId" to first), "speaker.say" to (speech + ("channelId" to first)),
                    "current_time" to mapOf("channelId" to first))
                val declared = answer(socket, submit(socket, source, owner, "clear provider"), """{"tools":[]}""")
                assertEquals(first, declared["chatId"].asText())
                assertEquals("speaker.say", declared["payload"]["name"].asText())
                assertEquals("thread.completed", answer(socket, declared)["type"].asText())
                assertEquals("skill_not_found", output()["error"]["code"].asText())
            }
        }

    @Test
    fun `equal definitions have one schema and conflicts hide the entire name until refreshed`() =
        backendE2eTest("e2e_catalogue_conflicts") {
            val owner = UUID.randomUUID().toString()
            val source = createPublicChat(owner)
            val first = createPublicChat(owner, "first", "[$definition,${definition.replace("speaker.say", "orion.tools.list")}]")
            val reordered = definition.replace("[\"text\",\"volume\"]", "[\"volume\",\"text\"]")
            val second = createPublicChat(owner, "second", "[$reordered,${definition.replace("speaker.say", "orion.tools.list").replace("Speak text", "Different discovery declaration")}]")
            // Foreign conflicting definitions must never affect this user's catalogue.
            createPublicChat(UUID.randomUUID().toString(), tools = "[${definition.replace("Speak text", "Foreign")}]")
            val speech = mapOf<String, Any>("text" to "hello", "volume" to 1)
            withMultiChatSocket { socket ->
                subscribe(socket, first, second)
                llm.requestSkillForPrompt("equal", "speaker.say", speech + ("channelId" to second))
                val equal = submit(socket, source, owner, "equal")
                val detail = json.readTree(llm.requests.last().messages.last { it.name == "GetSkillByName" }.content)["skill"]
                val description = detail["description"].asText()
                assertTrue(first in description && second in description)
                assertEquals(2, detail["inputSchema"]["properties"].size())
                assertEquals(second, equal["chatId"].asText())
                assertEquals("thread.completed", answer(socket, equal)["type"].asText())
                // The scripted model does not resolve ambiguity when two providers are connected.
                llm.requestSkillForPrompt("ambiguous", "speaker.say", speech)
                assertEquals("thread.completed", submit(socket, source, owner, "ambiguous")["type"].asText())
                assertEquals("client_tool_discovery_required", output()["error"]["code"].asText())
                for ((index, conflict) in listOf(
                    definition.replace("Speak text", "Different description"),
                    definition.replace("\"integer\"", "\"string\""),
                ).withIndex()) {
                    llm.requestSkillForPrompt("conflict-$index", "orion.tools.list", mapOf("channelId" to second))
                    assertEquals("thread.completed", answer(socket, submit(socket, source, owner, "conflict-$index"),
                        """{"tools":[$conflict]}""")["type"].asText())
                    assertEquals(0, output()["tools"].size())
                    llm.requestSkillForPrompt("hidden-$index", "speaker.say", speech + ("channelId" to first))
                    assertEquals("thread.completed", submit(socket, source, owner, "hidden-$index")["type"].asText())
                    assertEquals("skill_not_found", output()["error"]["code"].asText())
                    val inventory = llm.requests.last().messages.first().content
                    assertFalse("speaker.say" in inventory)
                    assertTrue("orion.tools.list" in inventory)
                }
                llm.requestSkillForPrompt("restore", "orion.tools.list", mapOf("channelId" to second), "speaker.say" to (speech + ("channelId" to first)))
                val restored = answer(socket, submit(socket, source, owner, "restore"), """{"tools":[]}""")
                assertEquals(first, restored["chatId"].asText())
                assertEquals("thread.completed", answer(socket, restored)["type"].asText())
            }
        }
    @Test
    fun `creation shares normalized HTTP and WS receipts and rejects invalid catalogues atomically`() =
        backendE2eTest("e2e_catalogue") {
            val owner = UUID.randomUUID().toString()
            withMultiChatSocket { socket ->
                suspend fun ws(id: String, tools: String?) = run {
                    val field = tools?.let { ",\"tools\":$it" }.orEmpty()
                    socket.send(Frame.Text("""{"kind":"chat.create","requestId":"$id","payload":{"userId":"$owner"$field}}"""))
                    readJson(socket)
                }
                suspend fun http(id: String, tools: String) = client.post(BackendHttpRoutes.CHATS) {
                    jsonBody("""{"userId":"$owner","requestId":"$id","clientType":"backend","tools":$tools}""")
                }
                val created = ws("create", "[$definition]")
                assertEquals("accepted", created["status"].asText())
                val reordered = definition.replace("[\"text\",\"volume\"]", "[\"volume\",\"text\",\"text\"]")
                val retry = http("create", "[$reordered]")
                assertEquals(HttpStatusCode.OK, retry.status)
                assertEquals(created["chatId"], retry.jsonBody()["chat"]["id"])
                assertTrue(retry.jsonBody()["duplicate"].asBoolean())
                assertEquals(HttpStatusCode.Conflict, http("create", "[]").status)
                assertEquals("idempotency_conflict", ws("create", "[${definition.replace("Speak text", "Changed")}]")["error"]["code"].asText())
                assertEquals("accepted", ws("legacy", null)["status"].asText())
                assertEquals(HttpStatusCode.OK, http("legacy", "[]").status)
                val nested = (1..17).fold("""{"type":"string"}""") { child, _ -> """{"type":"array","items":$child}""" }
                val invalid = listOf(
                    "null", "[null]", "[$definition,$definition]",
                    "[${definition.replace("speaker.say", "ConnectOAuthProvider")}]",
                    "[${definition.replace("speaker.say", "SafeApiCall")}]",
                    "[${definition.replace("speaker.say", "RunSkillCommand")}]",
                    "[${definition.replace("speaker.say", "personality.update")}]",
                    "[${definition.replace("speaker.say", "ExtractTextFromFile")}]",
                    "[${definition.replace("speaker.say", "bad name")}]",
                    "[${definition.replace("Speak text", " ")}]",
                    "[${definition.replace("\"Speak text\"", "123")}]",
                    "[${definition.replace("\"type\":\"integer\"", "\"type\":\"array\"")}]",
                    "[${definition.replace("\"type\":\"integer\"", "\"type\":\"integer\",\"minimum\":0")}]",
                    "[${definition.replace("{\"type\":\"integer\"}", nested)}]",
                    "[${definition.replace("\"text\"", "\"channelId\"")}]",
                    (0..256).joinToString(prefix = "[", postfix = "]") { definition.replace("speaker.say", "t$it") },
                    (0..64).joinToString(prefix = "[", postfix = "]") {
                        definition.replace("speaker.say", "t$it").replace("Speak text", "x".repeat(16_384))
                    },
                )
                invalid.forEachIndexed { index, tools ->
                    val id = "invalid-$index"
                    assertEquals("rejected", ws(id, tools)["status"].asText(), tools.take(100))
                    assertEquals(HttpStatusCode.BadRequest, http(id, tools).status)
                    assertEquals(HttpStatusCode.Created, http(id, "[]").status) // Rejection did not claim the receipt.
                }
                assertTrue(llm.requests.isEmpty())
            }
        }

    @Test
    fun `discovery survives restart and is shared without changing creation receipts`() {
        val schema = newPostgresSchema("e2e_catalogue_restart")
        val owner = UUID.randomUUID().toString()
        lateinit var provider: String
        backendE2eTest("e2e_catalogue_create", schema = schema) {
            provider = createPublicChat(owner, tools = "[$definition]")
            val legacySource = createPublicChat(owner, "legacy-source")
            backend.sql { connection ->
                connection.prepareStatement("update chats set discovered_client_tools = ?::jsonb where id = ?").use {
                    it.setString(1, """{"channelId":"$provider","tools":[${definition.replace("Speak text", "Stale conversation cache")}]}""")
                    it.setObject(2, UUID.fromString(legacySource))
                    it.executeUpdate()
                }
            }
            llm.requestSkillForPrompt("discover", "orion.tools.list", mapOf("channelId" to provider))
            withMultiChatSocket { socket ->
                assertEquals("thread.completed", answer(socket, submit(socket, provider, owner, "discover"),
                    """{"tools":[${definition.replace("Speak text", "Remembered speaker")}]}""")["type"].asText())
            }
        }
        backendE2eTest("e2e_catalogue_reload", schema = schema) {
            val retry = client.post(BackendHttpRoutes.CHATS) {
                jsonBody("""{"userId":"$owner","requestId":"create-1","clientType":"backend","tools":[$definition]}""")
            }
            assertEquals(HttpStatusCode.OK, retry.status)
            assertTrue(retry.jsonBody()["duplicate"].asBoolean())
            assertEquals(provider, retry.jsonBody()["chat"]["id"].asText())
            assertFalse(retry.jsonBody()["chat"].has("tools"))
            assertEquals(HttpStatusCode.Conflict, client.post(BackendHttpRoutes.CHATS) {
                jsonBody("""{"userId":"$owner","requestId":"create-1","clientType":"backend","tools":[]}""")
            }.status)
            val source = createPublicChat(owner, "empty")
            withMultiChatSocket { socket ->
                subscribe(socket, provider)
                for ((index, args) in listOf(
                    mapOf("text" to "missing volume"), mapOf("text" to "wrong", "volume" to "loud"),
                    mapOf("text" to "extra", "volume" to 1, "extra" to true),
                ).withIndex()) {
                    llm.requestSkillForPrompt("invalid-$index", "speaker.say", args + ("channelId" to provider))
                    assertEquals("thread.completed", submit(socket, source, owner, "invalid-$index")["type"].asText())
                    assertEquals("mcp_invalid_arguments", output()["error"]["code"].asText())
                }
                llm.requestSkillForPrompt("speak", "speaker.say", mapOf("text" to "hi", "volume" to 1, "channelId" to provider))
                val started = submit(socket, source, owner, "speak")
                assertEquals(provider, started["chatId"].asText())
                val detail = json.readTree(llm.requests.last().messages.last { it.name == "GetSkillByName" }.content)["skill"]
                assertTrue(detail["description"].asText().startsWith("Remembered speaker\n"))
                assertEquals("thread.completed", answer(socket, started, "[]")["type"].asText())
                assertEquals("invalid_tool_result", output()["error"]["code"].asText())
            }
        }
    }

    private suspend fun BackendE2eScope.subscribe(socket: DefaultClientWebSocketSession, vararg channels: String) {
        for (channel in channels) {
            socket.send(Frame.Text("""{"kind":"chat.subscribe","chatId":"$channel","requestId":"subscribe-$channel","afterSeq":9223372036854775807}"""))
            assertEquals("accepted", readJson(socket)["status"].asText())
        }
    }

    private suspend fun BackendE2eScope.submit(
        socket: DefaultClientWebSocketSession, chat: String, owner: String, prompt: String,
        deviceId: String = "history-device",
    ): JsonNode {
        socket.send(Frame.Text(messageFrame(chat, owner, prompt, text = prompt, deviceId = deviceId)))
        assertEquals("accepted", readJson(socket)["status"].asText())
        assertEquals("thread.status", readJson(socket)["type"].asText())
        return readJson(socket)
    }

    private suspend fun BackendE2eScope.answer(
        socket: DefaultClientWebSocketSession, started: JsonNode, result: String = """{"ok":true}""",
        errorCode: String? = null,
    ): JsonNode {
        assertEquals("tool.call.started", started["type"].asText(), started.toString())
        val outcome = if (errorCode == null) """"status":"succeeded","result":$result"""
            else """"status":"failed","error":{"code":"$errorCode","message":"Device failed"}"""
        socket.send(Frame.Text("""{"kind":"tool.result","chatId":${started["chatId"]},"threadId":${started["threadId"]},"toolCallId":${started["payload"]["toolCallId"]},$outcome}"""))
        assertEquals("accepted", readJson(socket)["status"].asText())
        return readJson(socket)
    }

    private fun BackendE2eScope.output(): JsonNode =
        json.readTree(llm.requests.last().messages.last { it.name == "RunSkillCommand" }.content)
}
