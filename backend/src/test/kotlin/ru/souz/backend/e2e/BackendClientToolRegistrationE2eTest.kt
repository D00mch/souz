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

class BackendClientToolRegistrationE2eTest {
    private val definition = """{"name":"speaker.say","description":"Speak text","parameters":{"type":"object","properties":{"text":{"type":"string"},"volume":{"type":"integer"}},"required":["text","volume"],"additionalProperties":false}}"""

    @Test
    fun `bundled defaults coexist with declarations and preserve schemas guidance and deadlines`() =
        backendE2eTest("e2e_bundled_defaults") {
            val owner = UUID.randomUUID().toString()
            val parameters = LLMRequest.Parameters(
                "object", mapOf("query" to LLMRequest.Property("string")),
                required = listOf("query"), additionalProperties = false,
            )
            val tools = json.writeValueAsString(listOf(ClientToolDefinition("device.media.open", "Custom media description", parameters)))
            val cases = listOf<Triple<String, Map<String, Any>, Long>>(
                Triple("user.ask", mapOf("question" to "Which genre?"), 300),
                Triple("device.media.open", mapOf("query" to "The Thing"), 60),
                Triple("device.mcp.list_devices", emptyMap(), 30),
                Triple("device.mcp.list_tools", mapOf("target" to "speaker"), 30),
                Triple("device.mcp.call_tool", mapOf("name" to "set_volume", "arguments" to mapOf("level" to 7)), 60),
                Triple("web.search", mapOf("query" to "Kotlin release"), 60),
                Triple("orion.tools.list", emptyMap(), 30),
            )
            val chat = createPublicChat(owner, tools = tools)
            withMultiChatSocket { socket ->
                for ((name, arguments, seconds) in cases) {
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
                        assertEquals(json.valueToTree<JsonNode>(parameters), detail["inputSchema"])
                    } else if (name != "orion.tools.list") {
                        assertEquals(json.valueToTree<JsonNode>(LLMRequest.Parameters("object")), detail["inputSchema"])
                    }
                    val result = if (name == "orion.tools.list") """{"tools":[]}""" else """{"ok":true}"""
                    assertEquals("thread.completed", answer(socket, started, result)["type"].asText())
                }
                llm.requestSkillForPrompt("invalid schema", "device.media.open", mapOf("text" to "wrong schema"))
                assertEquals("thread.completed", submit(socket, chat, owner, "invalid schema")["type"].asText())
                assertEquals("mcp_invalid_arguments", output()["error"]["code"].asText())
            }
            withMultiChatSocket { socket ->
                socket.send(Frame.Text("""{"kind":"chat.create","requestId":"empty-tools","payload":{"userId":"$owner","tools":[]}}"""))
                val created = readJson(socket)
                assertEquals("accepted", created["status"].asText())
                llm.requestSkillForPrompt("empty array", "device.media.open", emptyMap(), "orion.call" to emptyMap())
                val started = submit(socket, created["chatId"].asText(), owner, "empty array")
                assertEquals("device.media.open", started["payload"]["name"].asText())
                assertEquals("thread.completed", answer(socket, started)["type"].asText())
                assertEquals("skill_not_found", output()["error"]["code"].asText())
            }
        }

    @Test
    fun `discovery refreshes immediately binds its channel and preserves the catalogue on invalid results`() =
        backendE2eTest("e2e_discovery_refresh") {
            val owner = UUID.randomUUID().toString()
            val source = createPublicChat(owner, tools = "[$definition]")
            val first = createPublicChat(owner, "first")
            val second = createPublicChat(owner, "second")
            val clock = """{"name":"current_time","description":"Current time","parameters":{"type":"object","additionalProperties":false}}"""
            val speaker = definition.replace("Speak text", "Discovered speaker")
            val speech = mapOf<String, Any>("text" to "hello", "volume" to 1)
            withMultiChatSocket { socket ->
                for (channel in listOf(first, second)) {
                    socket.send(Frame.Text("""{"kind":"chat.subscribe","chatId":"$channel","requestId":"subscribe-$channel"}"""))
                    assertEquals("accepted", readJson(socket)["status"].asText())
                }
                llm.requestSkillForPrompt("local discovery", "orion.tools.list", emptyMap(), "current_time" to emptyMap())
                val discovery = submit(socket, source, owner, "local discovery")
                assertEquals("orion.tools.list", discovery["payload"]["name"].asText())
                val large = """{"tools":[${clock.replace("Current time", "x".repeat(9_000))}]}"""
                val local = answer(socket, discovery, large)
                assertEquals("current_time", local["payload"]["name"].asText())
                assertEquals(source, local["chatId"].asText())
                assertFalse(local["seq"].isNull)
                assertTrue(output().has("knowledgeId"))
                assertEquals("thread.completed", answer(socket, local)["type"].asText())

                llm.requestSkillForPrompt("remote discovery", "orion.tools.list", mapOf("channelId" to first), "speaker.say" to speech)
                val remote = submit(socket, source, owner, "remote discovery")
                assertEquals(first, remote["chatId"].asText())
                assertEquals(json.readTree("{}"), remote["payload"]["arguments"])
                val spoken = answer(socket, remote, """{"tools":[$clock,$speaker]}""")
                assertEquals(first, spoken["chatId"].asText())
                assertTrue(spoken["seq"].isNull)
                assertEquals(json.valueToTree<JsonNode>(speech), spoken["payload"]["arguments"])
                val detail = json.readTree(llm.requests.last().messages.last { it.name == "GetSkillByName" }.content)["skill"]
                assertEquals("Discovered speaker", detail["description"].asText())
                assertEquals("thread.completed", answer(socket, spoken)["type"].asText())

                val failures = listOf(
                    "strict" to """{"tools":[],"unknown":true}""",
                    "self" to """{"tools":[${clock.replace("current_time", "orion.tools.list")}]}""",
                    "transport" to "{}",
                    "storage" to """{"tools":[$clock]}""",
                )
                for ((failure, result) in failures) {
                    val prompt = "$failure refresh"
                    llm.requestSkillForPrompt(prompt, "orion.tools.list", mapOf("channelId" to second), "current_time" to emptyMap())
                    val refresh = submit(socket, source, owner, prompt)
                    assertEquals(second, refresh["chatId"].asText())
                    if (failure == "storage") backend.sql { connection ->
                        connection.createStatement().use { it.execute(
                            "alter table chats add constraint reject_discovery check (discovered_client_tools->>'channelId' <> '$second') not valid",
                        ) }
                    }
                    val retained = try {
                        answer(socket, refresh, result, errorCode = "client_tool_failed".takeIf { failure == "transport" })
                    } finally {
                        if (failure == "storage") backend.sql { connection ->
                            connection.createStatement().use { it.execute("alter table chats drop constraint reject_discovery") }
                        }
                    }
                    val expected = if (failure in listOf("strict", "self")) "invalid_client_tool_catalogue" else "client_tool_failed"
                    assertEquals(expected, output()["error"]["code"].asText())
                    assertEquals("current_time", retained["payload"]["name"].asText())
                    assertEquals(first, retained["chatId"].asText())
                    assertEquals("thread.completed", answer(socket, retained)["type"].asText())
                }
                llm.requestSkillForPrompt("override binding", "current_time", mapOf("channelId" to second))
                assertEquals("thread.completed", submit(socket, source, owner, "override binding")["type"].asText())
                assertEquals("client_context_mismatch", output()["error"]["code"].asText())

                llm.requestSkillForPrompt(
                    "replace", "orion.tools.list", mapOf("channelId" to second),
                    "orion.call" to mapOf("text" to "stop", "volume" to 1), "current_time" to emptyMap(),
                )
                val replacement = submit(socket, source, owner, "replace")
                val orion = answer(socket, replacement, """{"tools":[${speaker.replace("speaker.say", "orion.call")}]}""")
                assertEquals(second, orion["chatId"].asText())
                assertEquals("orion.call", orion["payload"]["name"].asText())
                assertEquals("Discovered speaker", json.readTree(llm.requests.last().messages.last { it.name == "GetSkillByName" }.content)["skill"]["description"].asText())
                assertEquals("thread.completed", answer(socket, orion)["type"].asText())
                assertEquals("skill_not_found", output()["error"]["code"].asText())

                llm.requestSkillForPrompt(
                    "empty refresh", "orion.tools.list", mapOf("channelId" to second),
                    "speaker.say" to speech, "device.media.open" to mapOf("query" to "The Thing"), "orion.call" to emptyMap(),
                )
                val empty = submit(socket, source, owner, "empty refresh")
                val declared = answer(socket, empty, """{"tools":[]}""")
                assertEquals(source, declared["chatId"].asText())
                assertEquals("speaker.say", declared["payload"]["name"].asText())
                assertEquals("Speak text", json.readTree(llm.requests.last().messages.last { it.name == "GetSkillByName" }.content)["skill"]["description"].asText())
                val bundled = answer(socket, declared)
                assertEquals(source, bundled["chatId"].asText())
                assertEquals("device.media.open", bundled["payload"]["name"].asText())
                assertEquals("thread.completed", answer(socket, bundled)["type"].asText())
                assertEquals("skill_not_found", output()["error"]["code"].asText())
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
    fun `remembered discovery survives restart without changing creation receipts or leaking between chats`() {
        val schema = newPostgresSchema("e2e_catalogue_restart")
        val owner = UUID.randomUUID().toString()
        lateinit var chat: String
        backendE2eTest("e2e_catalogue_create", schema = schema) {
            chat = createPublicChat(owner, tools = "[$definition]")
            llm.requestSkillForPrompt("discover", "orion.tools.list", emptyMap())
            withMultiChatSocket { socket ->
                val started = submit(socket, chat, owner, "discover")
                assertEquals("thread.completed", answer(
                    socket, started, """{"tools":[${definition.replace("Speak text", "Remembered speaker")}]}""",
                )["type"].asText())
            }
        }
        backendE2eTest("e2e_catalogue_reload", schema = schema) {
            val retry = client.post(BackendHttpRoutes.CHATS) {
                jsonBody("""{"userId":"$owner","requestId":"create-1","clientType":"backend","tools":[$definition]}""")
            }
            assertEquals(HttpStatusCode.OK, retry.status)
            assertTrue(retry.jsonBody()["duplicate"].asBoolean())
            assertEquals(chat, retry.jsonBody()["chat"]["id"].asText())
            assertFalse(retry.jsonBody()["chat"].has("tools"))
            assertEquals(HttpStatusCode.Conflict, client.post(BackendHttpRoutes.CHATS) {
                jsonBody("""{"userId":"$owner","requestId":"create-1","clientType":"backend","tools":[]}""")
            }.status)
            val empty = createPublicChat(owner, "empty")
            for ((target, arguments, expected) in listOf(
                Triple(empty, emptyMap(), "skill_not_found"),
                Triple(chat, mapOf("text" to "missing volume"), "mcp_invalid_arguments"),
                Triple(chat, mapOf("text" to "wrong", "volume" to "loud"), "mcp_invalid_arguments"),
                Triple(chat, mapOf("text" to "extra", "volume" to 1, "extra" to true), "mcp_invalid_arguments"),
            )) {
                val prompt = UUID.randomUUID().toString()
                llm.requestSkillForPrompt(prompt, "speaker.say", arguments)
                withMultiChatSocket { socket ->
                    assertEquals("thread.completed", submit(socket, target, owner, prompt)["type"].asText())
                    assertEquals(expected, output()["error"]["code"].asText())
                    if (target == empty) assertTrue("web.search" in llm.requests.last().messages.first().content)
                }
            }
            repeat(2) { turn ->
                val prompt = "speak-$turn"
                val arguments = mapOf("text" to prompt, "volume" to turn)
                if (turn == 0) llm.requestSkillForPrompt(prompt, "speaker.say", arguments)
                else llm.requestSkillForPrompt(prompt, "orion.tools.list", emptyMap(), "speaker.say" to arguments)
                withMultiChatSocket { socket ->
                    val first = submit(socket, chat, owner, prompt, "device-$turn")
                    val started = if (turn == 0) first else answer(socket, first, """{"tools":[]}""")
                    assertEquals("speaker.say", started["payload"]["name"].asText())
                    assertEquals("device-$turn", started["payload"]["deviceId"].asText())
                    assertEquals(prompt, started["payload"]["arguments"]["text"].asText())
                    val detail = json.readTree(llm.requests.last().messages.last { it.name == "GetSkillByName" }.content)["skill"]
                    assertEquals(if (turn == 0) "Remembered speaker" else "Speak text", detail["description"].asText())
                    assertEquals(json.valueToTree<JsonNode>(
                        json.readValue(definition, ClientToolDefinition::class.java).parameters,
                    ), detail["inputSchema"])
                    val result = if (turn == 0) """{"spoken":true}""" else "[]"
                    assertEquals("thread.completed", answer(socket, started, result)["type"].asText())
                    if (turn == 1) {
                        assertEquals("invalid_tool_result", output()["error"]["code"].asText())
                    }
                }
            }
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
