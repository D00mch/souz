package ru.souz.backend.e2e

import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import java.util.UUID
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.storage.postgres.newPostgresSchema
import ru.souz.backend.client.ClientToolDefinition
import ru.souz.llms.LLMRequest

class BackendClientToolRegistrationE2eTest {
    private val definition = """{"name":"speaker.say","description":"Speak text","parameters":{"type":"object","properties":{"text":{"type":"string"},"volume":{"type":"integer"}},"required":["text","volume"],"additionalProperties":false}}"""

    @Test
    fun `empty catalogues expose all bundled Skills with guidance and original deadlines`() =
        backendE2eTest("e2e_bundled_defaults") {
            val owner = UUID.randomUUID().toString()
            val cases = listOf<Triple<String, Map<String, Any>, Long>>(
                Triple("user.ask", mapOf("question" to "Which genre?"), 300),
                Triple("device.media.open", mapOf("query" to "The Thing"), 60),
                Triple("device.mcp.list_devices", emptyMap(), 30),
                Triple("device.mcp.list_tools", mapOf("target" to "speaker"), 30),
                Triple("device.mcp.call_tool", mapOf("name" to "set_volume", "arguments" to mapOf("level" to 7)), 60),
                Triple("web.search", mapOf("query" to "Kotlin release"), 60),
                Triple("orion.call", mapOf("utterance" to "включи Pink Floyd"), 60),
            )
            val chat = createPublicChat(owner)
            withMultiChatSocket { socket ->
                for ((name, arguments, seconds) in cases) {
                    llm.requestSkillForPrompt(name, name, arguments)
                    val before = Instant.now()
                    socket.send(Frame.Text(messageFrame(chat, owner, name, text = name)))
                    assertEquals("accepted", readJson(socket)["status"].asText())
                    assertEquals("thread.status", readJson(socket)["type"].asText())
                    val started = readJson(socket)
                    val after = Instant.now()
                    val payload = started["payload"]
                    assertEquals(name, payload["name"].asText())
                    assertEquals(json.valueToTree<com.fasterxml.jackson.databind.JsonNode>(arguments), payload["arguments"])
                    val deadline = Instant.parse(payload["deadlineAt"].asText())
                    assertTrue(deadline >= before.plusSeconds(seconds) && deadline <= after.plusSeconds(seconds))
                    val detail = json.readTree(llm.requests.last().messages.last { it.name == "GetSkillByName" }.content)["skill"]
                    assertTrue("RunSkillCommand" in detail["description"].asText())
                    assertEquals(json.valueToTree<com.fasterxml.jackson.databind.JsonNode>(LLMRequest.Parameters("object")), detail["inputSchema"])
                    socket.send(Frame.Text("""{"kind":"tool.result","chatId":"$chat","threadId":${started["threadId"]},"toolCallId":${payload["toolCallId"]},"status":"succeeded","result":{"ok":true}}"""))
                    assertEquals("accepted", readJson(socket)["status"].asText())
                    assertEquals("thread.completed", readJson(socket)["type"].asText())
                }
            }
            withMultiChatSocket { socket ->
                socket.send(Frame.Text("""{"kind":"chat.create","requestId":"empty-tools","payload":{"userId":"$owner","tools":[]}}"""))
                val created = readJson(socket)
                assertEquals("accepted", created["status"].asText())
                llm.requestSkillForPrompt("empty array", "orion.call", emptyMap())
                socket.send(Frame.Text(messageFrame(created["chatId"].asText(), owner, "empty-array", text = "empty array")))
                assertEquals("accepted", readJson(socket)["status"].asText())
                readJson(socket)
                val started = readJson(socket)
                assertEquals("orion.call", started["payload"]["name"].asText())
                socket.send(Frame.Text("""{"kind":"tool.result","chatId":${created["chatId"]},"threadId":${started["threadId"]},"toolCallId":${started["payload"]["toolCallId"]},"status":"succeeded","result":{"reply":"done"}}"""))
                assertEquals("accepted", readJson(socket)["status"].asText())
                assertEquals("thread.completed", readJson(socket)["type"].asText())
            }
        }

    @Test
    fun `declarations override matching defaults and retain undeclared client Skills`() =
        backendE2eTest("e2e_bundled_declared") {
            val owner = UUID.randomUUID().toString()
            val parameters = LLMRequest.Parameters(
                "object", mapOf("utterance" to LLMRequest.Property("string")),
                required = listOf("utterance"), additionalProperties = false,
            )
            val tools = json.writeValueAsString(listOf(ClientToolDefinition("orion.call", "Custom Orion description", parameters)))
            val chat = createPublicChat(owner, tools = tools)
            val cases = listOf<Triple<String, Map<String, Any>, String>>(
                Triple("orion.call", mapOf("utterance" to "stop"), """{"reply":"stopped"}"""),
                Triple("web.search", mapOf("query" to "news"), """{"documents":[]}"""),
                Triple("orion.tools.list", emptyMap(), """{"tools":[]}"""),
            )
            withMultiChatSocket { socket ->
                for ((name, arguments, result) in cases) {
                    llm.requestSkillForPrompt(name, name, arguments)
                    socket.send(Frame.Text(messageFrame(chat, owner, name, text = name)))
                    assertEquals("accepted", readJson(socket)["status"].asText())
                    readJson(socket)
                    val started = readJson(socket)
                    assertEquals(name, started["payload"]["name"].asText())
                    val detail = json.readTree(llm.requests.last().messages.last { it.name == "GetSkillByName" }.content)["skill"]
                    assertTrue("RunSkillCommand" in detail["description"].asText())
                    if (name == "orion.call") {
                        assertTrue(detail["description"].asText().startsWith("Custom Orion description\n\n"))
                        assertEquals(json.valueToTree<com.fasterxml.jackson.databind.JsonNode>(parameters), detail["inputSchema"])
                    }
                    socket.send(Frame.Text("""{"kind":"tool.result","chatId":"$chat","threadId":${started["threadId"]},"toolCallId":${started["payload"]["toolCallId"]},"status":"succeeded","result":$result}"""))
                    assertEquals("accepted", readJson(socket)["status"].asText())
                    assertEquals("thread.completed", readJson(socket)["type"].asText())
                }
                llm.requestSkillForPrompt("invalid", "orion.call", mapOf("text" to "wrong schema"))
                socket.send(Frame.Text(messageFrame(chat, owner, "invalid", text = "invalid")))
                assertEquals("accepted", readJson(socket)["status"].asText())
                readJson(socket)
                assertEquals("thread.completed", readJson(socket)["type"].asText())
                val result = llm.requests.last().messages.last { it.name == "RunSkillCommand" }
                assertEquals("mcp_invalid_arguments", json.readTree(result.content)["error"]["code"].asText())
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
                    "[${definition.replace("speaker.say", "ReadFile")}]",
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
    fun `persisted catalogues survive runtimes reconnects and threads without leaking between chats`() {
        val schema = newPostgresSchema("e2e_catalogue_restart")
        val owner = UUID.randomUUID().toString()
        lateinit var chat: String
        backendE2eTest("e2e_catalogue_create", schema = schema) {
            chat = createPublicChat(owner, tools = "[$definition]")
        }
        backendE2eTest("e2e_catalogue_reload", schema = schema) {
            repeat(2) { turn ->
                val prompt = "speak-$turn"
                llm.requestSkillForPrompt(prompt, "speaker.say", mapOf("text" to prompt, "volume" to turn))
                withMultiChatSocket { socket ->
                    socket.send(Frame.Text(messageFrame(chat, owner, prompt, text = prompt, deviceId = "device-$turn")))
                    assertEquals("accepted", readJson(socket)["status"].asText())
                    readJson(socket)
                    val started = readJson(socket)
                    assertEquals("speaker.say", started["payload"]["name"].asText())
                    assertEquals("device-$turn", started["payload"]["deviceId"].asText())
                    assertEquals(prompt, started["payload"]["arguments"]["text"].asText())
                    val detail = json.readTree(llm.requests.last().messages.last { it.name == "GetSkillByName" }.content)["skill"]
                    assertEquals("Speak text", detail["description"].asText())
                    assertEquals(json.valueToTree<com.fasterxml.jackson.databind.JsonNode>(
                        json.readValue(definition, ru.souz.backend.client.ClientToolDefinition::class.java).parameters,
                    ), detail["inputSchema"])
                    val result = if (turn == 0) """{"spoken":true}""" else "[]"
                    socket.send(Frame.Text("""{"kind":"tool.result","chatId":"$chat","threadId":${started["threadId"]},"toolCallId":${started["payload"]["toolCallId"]},"status":"succeeded","result":$result}"""))
                    assertEquals("accepted", readJson(socket)["status"].asText())
                    assertEquals("thread.completed", readJson(socket)["type"].asText())
                    if (turn == 1) {
                        val output = llm.requests.last().messages.last { it.name == "RunSkillCommand" }
                        assertEquals("invalid_tool_result", json.readTree(output.content)["error"]["code"].asText())
                    }
                }
            }
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
                    socket.send(Frame.Text(messageFrame(target, owner, prompt, text = prompt)))
                    assertEquals("accepted", readJson(socket)["status"].asText())
                    readJson(socket)
                    assertEquals("thread.completed", readJson(socket)["type"].asText())
                    val result = llm.requests.last().messages.last { it.name == "RunSkillCommand" }
                    assertEquals(expected, json.readTree(result.content)["error"]["code"].asText())
                    if (target == empty) assertTrue("web.search" in llm.requests.last().messages.first().content)
                }
            }
        }
    }
}
