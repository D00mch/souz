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

class BackendClientToolRegistrationE2eTest {
    private val definition = """{"name":"speaker.say","description":"Speak text","parameters":{"type":"object","properties":{"text":{"type":"string"},"volume":{"type":"integer"}},"required":["text","volume"],"additionalProperties":false}}"""

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
                    if (target == empty) assertFalse("web.search" in llm.requests.last().messages.first().content)
                }
            }
        }
    }
}
