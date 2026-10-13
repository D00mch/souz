package ru.souz.backend.e2e

import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.storage.postgres.newPostgresSchema
import ru.souz.llms.LLMMessageRole

class BackendClientToolRegistrationE2eTest {
    private val definition = """{"name":"speaker.say","description":"Speak the supplied text.","parameters":{"type":"object","properties":{"text":{"type":"string"},"volume":{"type":"integer"}},"required":["text","volume"],"additionalProperties":false}}"""

    @Test
    fun `HTTP and WS share normalized catalogue idempotency and reject invalid creation atomically`() =
        backendE2eTest("e2e_tool_registration") {
            val owner = UUID.randomUUID().toString()
            withMultiChatSocket { socket ->
                suspend fun create(id: String, tools: String?): com.fasterxml.jackson.databind.JsonNode {
                    socket.send(Frame.Text("""{"kind":"chat.create","requestId":"$id","payload":{"userId":"$owner"${tools?.let { ",\"tools\":$it" }.orEmpty()}}}"""))
                    return readJson(socket)
                }
                suspend fun http(id: String, tools: String) = client.post(BackendHttpRoutes.CHATS) {
                    jsonBody("""{"userId":"$owner","requestId":"$id","clientType":"backend","tools":$tools}""")
                }
                val created = create("catalogue", "[$definition]")
                assertEquals("accepted", created["status"].asText())
                val reordered = definition.replace("[\"text\",\"volume\"]", "[\"volume\",\"text\"]")
                val retry = http("catalogue", "[$reordered]")
                assertEquals(HttpStatusCode.OK, retry.status)
                assertTrue(retry.jsonBody()["duplicate"].asBoolean())
                assertEquals(created["chatId"], retry.jsonBody()["chat"]["id"])
                for (changed in listOf("[]", "[${definition.replace("Speak the supplied text.", "Other description")}]",
                    "[${definition.replace("integer", "number")}]")) {
                    assertEquals(HttpStatusCode.Conflict, http("catalogue", changed).status)
                    assertEquals("idempotency_conflict", create("catalogue", changed)["error"]["code"].asText())
                }
                for (empty in listOf(null, "[]")) {
                    val id = "empty-$empty"
                    val ack = create(id, empty)
                    assertEquals("accepted", ack["status"].asText())
                    assertEquals(HttpStatusCode.OK, http(id, "[]").status)
                    assertTrue(create(id, null)["duplicate"].asBoolean())
                }
                val second = definition.replace("speaker.say", "speaker.stop")
                assertEquals(HttpStatusCode.Created, http("ordered", "[$definition,$second]").status)
                assertTrue(create("ordered", "[$second,$definition]")["duplicate"].asBoolean())
                val deepSchema = (1..17).fold("""{"type":"string"}""") { nested, _ ->
                    """{"type":"array","items":$nested}"""
                }
                val invalid = listOf(
                    "null", "{}", "[null]", "[$definition,$definition]",
                    "[${definition.replace("\"Speak the supplied text.\"", "123")}]",
                    "[${definition.replace("\"type\":\"string\"", "\"type\":\"string\",\"enum\":[1]")}]",
                    "[${definition.replace("\"type\":\"string\"", "\"type\":\"string\",\"enum\":[null]")}]",
                    "[${definition.replace("speaker.say", "RunSkillCommand")}]",
                    "[${definition.replace("speaker.say", "ReadFile")}]",
                    "[${definition.replace("speaker.say", "bad name")}]",
                    "[${definition.replace("Speak the supplied text.", " ")}]",
                    "[${definition.replace("Speak the supplied text.", "x".repeat(16_385))}]",
                    "[${definition.replace("\"integer\"", "\"unsupported\"")}]",
                    "[${definition.replace("\"integer\"", "\"array\"")}]",
                    "[${definition.replace("\"type\":\"integer\"", "\"type\":\"integer\",\"enum\":[\"1\"]")}]",
                    "[${definition.replace("\"type\":\"integer\"", "\"type\":\"integer\",\"minimum\":0")}]",
                    "[${definition.replace("{\"type\":\"integer\"}", deepSchema)}]",
                    "[${definition.replace("\"required\":[\"text\",\"volume\"]", "\"required\":[\"missing\"]")}]",
                    "[${definition.replace("\"text\"", "\"channelId\"")}]",
                    (0..256).joinToString(prefix = "[", postfix = "]") { definition.replace("speaker.say", "tool$it") },
                    (0..64).joinToString(prefix = "[", postfix = "]") {
                        definition.replace("speaker.say", "tool$it").replace("Speak the supplied text.", "x".repeat(16_384))
                    },
                )
                invalid.forEachIndexed { index, tools ->
                    val id = "invalid-$index"
                    assertEquals("rejected", create(id, tools)["status"].asText(), tools.take(200))
                    assertEquals(HttpStatusCode.BadRequest, http(id, tools).status)
                    // The rejected request did not create a chat or claim its idempotency key.
                    assertEquals(HttpStatusCode.Created, http(id, "[]").status)
                }
                assertTrue(llm.requests.isEmpty())
            }
        }

    @Test
    fun `catalogue survives restart and reconnect with isolated chats and exact Skill definitions`() {
        val schema = newPostgresSchema("e2e_persisted_tools")
        val owner = UUID.randomUUID().toString()
        lateinit var chat: String
        backendE2eTest("e2e_tools_create", schema = schema) {
            chat = createPublicChat(owner, tools = "[$definition]")
        }
        backendE2eTest("e2e_tools_restart", schema = schema) {
            repeat(2) { turn ->
                val prompt = "speak-$turn"
                llm.requestSkillForPrompt(prompt, "speaker.say", mapOf("text" to prompt, "volume" to turn))
                withMultiChatSocket { socket ->
                    socket.send(Frame.Text(messageFrame(chat, owner, prompt, text = prompt, deviceId = "device-$turn")))
                    assertEquals("accepted", readJson(socket)["status"].asText())
                    readJson(socket) // thread.status
                    val started = readJson(socket)
                    assertEquals("speaker.say", started["payload"]["name"].asText())
                    assertEquals("device-$turn", started["payload"]["deviceId"].asText())
                    assertEquals(prompt, started["payload"]["arguments"]["text"].asText())
                    val discovered = json.readTree(llm.requests.last().messages.last { it.name == "GetSkillByName" }.content)["skill"]
                    assertEquals("Speak the supplied text.", discovered["description"].asText())
                    assertEquals(json.valueToTree<com.fasterxml.jackson.databind.JsonNode>(
                        json.readValue(definition, ru.souz.backend.client.ClientToolDefinition::class.java).parameters,
                    ), discovered["inputSchema"])
                    socket.send(Frame.Text("""{"kind":"tool.result","chatId":"$chat","threadId":${started["threadId"]},"toolCallId":${started["payload"]["toolCallId"]},"status":"succeeded","result":{"spoken":true}}"""))
                    assertEquals("accepted", readJson(socket)["status"].asText())
                    assertEquals("thread.completed", readJson(socket)["type"].asText())
                }
            }
            val empty = createPublicChat(owner, "empty")
            llm.requestSkillForPrompt("unavailable", "speaker.say", emptyMap())
            withPublicSocket(empty) { socket ->
                socket.send(Frame.Text(messageFrame(empty, owner, "empty-turn", text = "unavailable")))
                readJson(socket)
                readJson(socket)
                assertEquals("thread.completed", readJson(socket)["type"].asText())
                val inventory = llm.requests.last().messages.first { it.role == LLMMessageRole.system }.content
                assertFalse(inventory.contains("speaker.say"))
                assertFalse(inventory.contains("web.search"))
                assertTrue(inventory.contains("ListActiveChannels"))
            }
            val invalidArguments = listOf(
                mapOf("text" to "missing volume"),
                mapOf("text" to "wrong type", "volume" to "loud"),
                mapOf("text" to "unknown argument", "volume" to 1, "extra" to true),
            )
            invalidArguments.forEachIndexed { index, arguments ->
                val prompt = "invalid-$index"
                llm.requestSkillForPrompt(prompt, "speaker.say", arguments)
                withMultiChatSocket { socket ->
                    socket.send(Frame.Text(messageFrame(chat, owner, prompt, text = prompt)))
                    readJson(socket)
                    readJson(socket)
                    assertEquals("thread.completed", readJson(socket)["type"].asText())
                    val result = llm.requests.last().messages.last { it.name == "RunSkillCommand" }
                    assertEquals("mcp_invalid_arguments", json.readTree(result.content)["error"]["code"].asText())
                }
            }
        }
    }
}
