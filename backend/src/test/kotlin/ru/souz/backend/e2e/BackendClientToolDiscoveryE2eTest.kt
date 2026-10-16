package ru.souz.backend.e2e

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.restJsonMapper

class BackendClientToolDiscoveryE2eTest {
    private val definition = """{"name":"speaker.say","description":"Speak text","parameters":{"type":"object","properties":{"text":{"type":"string"},"volume":{"type":"integer"}},"required":["text","volume"],"additionalProperties":false}}"""

    @Test
    fun `HTTP source discovers a large Orion catalogue and calls bound tools only for that execution`() {
        lateinit var target: String
        val otherTarget = UUID.randomUUID().toString()
        val declaredSchema = restJsonMapper.valueToTree<JsonNode>(restJsonMapper.treeToValue(
            restJsonMapper.readTree(definition)["parameters"], LLMRequest.Parameters::class.java,
        ))
        val llm = E2eLlmApi { request ->
            val results = request.currentResults()
            if (request.conversationPrompt() == "later") {
                when (results.size) {
                    0 -> toolCallReply(request, "GetSkillByName", mapOf("skillId" to "speaker.say"))
                    1 -> {
                        assertEquals("skill_not_found", restJsonMapper.readTree(results.last().content)["error"]["code"].asText())
                        toolCallReply(request, "RunSkillCommand", mapOf("skillId" to "speaker.say"))
                    }
                    else -> {
                        assertEquals("skill_not_found", restJsonMapper.readTree(results.last().content)["error"]["code"].asText())
                        reply(request, "temporary tools expired")
                    }
                }
            } else when (results.size) {
                0 -> toolCallReply(request, "GetSkillByName", mapOf("skillId" to "orion.tools.list"))
                1 -> {
                    assertEquals("orion.tools.list", restJsonMapper.readTree(results.last().content)["skill"]["skillId"].asText())
                    toolCallReply(request, "RunSkillCommand", mapOf(
                        "skillId" to "orion.tools.list", "arguments" to mapOf("channelId" to target),
                    ))
                }
                2 -> {
                    assertTrue(restJsonMapper.readTree(results.last().content).has("knowledgeId"))
                    toolCallReply(request, "GetSkillByName", mapOf("skillId" to "speaker.say"))
                }
                3 -> {
                    val detail = restJsonMapper.readTree(results.last().content)["skill"]
                    assertEquals(declaredSchema, detail["inputSchema"])
                    assertEquals("x".repeat(9_000), detail["description"].asText())
                    toolCallReply(request, "RunSkillCommand", mapOf(
                        "skillId" to "speaker.say", "arguments" to mapOf("channelId" to otherTarget, "text" to "Hello", "volume" to 3),
                    ))
                }
                4 -> {
                    assertEquals("client_context_mismatch", restJsonMapper.readTree(results.last().content)["error"]["code"].asText())
                    toolCallReply(request, "RunSkillCommand", mapOf(
                        "skillId" to "speaker.say", "arguments" to mapOf("text" to "Hello", "volume" to "loud"),
                    ))
                }
                5 -> {
                    assertEquals("mcp_invalid_arguments", restJsonMapper.readTree(results.last().content)["error"]["code"].asText())
                    toolCallReply(request, "RunSkillCommand", mapOf(
                        "skillId" to "speaker.say", "arguments" to mapOf("text" to "Hello", "volume" to 3),
                    ))
                }
                else -> {
                    assertTrue(restJsonMapper.readTree(results.last().content)["spoken"].asBoolean())
                    reply(request, "discovered tool completed")
                }
            }
        }
        backendE2eTest("e2e_discovery_bound", llm = llm) {
            val user = UUID.randomUUID().toString()
            val source = createPublicChat(user, "source")
            target = createPublicChat(user, "target")
            withMultiChatSocket { socket ->
                subscribe(socket, target)
                val before = Instant.now()
                val execution = submitHttp(user, source, "discover")
                val discovery = readJson(socket)
                assertEquals("orion.tools.list", discovery["payload"]["name"].asText())
                assertEquals(target, discovery["chatId"].asText())
                assertTrue(discovery["seq"].isNull)
                assertEquals(json.readTree("{}"), discovery["payload"]["arguments"])
                val deadline = Instant.parse(discovery["payload"]["deadlineAt"].asText())
                assertTrue(deadline >= before.plusSeconds(30) && deadline <= Instant.now().plusSeconds(30))
                val largeDefinition = definition.replace("Speak text", "x".repeat(9_000))
                respond(socket, discovery, """{"tools":[$largeDefinition]}""")

                val invoked = readJson(socket)
                assertEquals("speaker.say", invoked["payload"]["name"].asText())
                assertEquals(target, invoked["chatId"].asText())
                assertTrue(invoked["seq"].isNull)
                assertEquals(json.readTree("""{"text":"Hello","volume":3}"""), invoked["payload"]["arguments"])
                assertFalse(invoked["payload"]["arguments"].has("channelId"))
                respond(socket, invoked, """{"spoken":true}""")
                assertCompleted(user, source, execution, "discovered tool completed")

                val later = submitHttp(user, source, "later")
                assertCompleted(user, source, later, "temporary tools expired")
                val retried = client.post(BackendHttpRoutes.CHATS) {
                    jsonBody("""{"userId":"$user","requestId":"source","clientType":"backend","tools":[]}""")
                }
                assertEquals(HttpStatusCode.OK, retried.status)
                assertEquals(source, retried.jsonBody()["chat"]["id"].asText())
                assertTrue(retried.jsonBody()["duplicate"].asBoolean())
            }
        }
    }

    @Test
    fun `HTTP execution invokes discovered tools through its own subscribed chat without a current device`() {
        lateinit var chat: String
        val llm = E2eLlmApi { request ->
            when (request.currentResults().size) {
                0 -> toolCallReply(request, "RunSkillCommand", mapOf(
                    "skillId" to "orion.tools.list", "arguments" to mapOf("channelId" to chat),
                ))
                1 -> toolCallReply(request, "GetSkillByName", mapOf("skillId" to "speaker.say"))
                2 -> toolCallReply(request, "RunSkillCommand", mapOf(
                    "skillId" to "speaker.say", "arguments" to mapOf("text" to "Same chat", "volume" to 2),
                ))
                else -> {
                    assertTrue(restJsonMapper.readTree(request.currentResults().last().content)["spoken"].asBoolean())
                    reply(request, "same chat tool completed")
                }
            }
        }
        backendE2eTest("e2e_discovery_same_chat", llm = llm) {
            val user = UUID.randomUUID().toString()
            chat = createPublicChat(user)
            withMultiChatSocket { socket ->
                subscribe(socket, chat)
                val execution = submitHttp(user, chat, "same chat")
                val discovery = readJson(socket)
                assertEquals("orion.tools.list", discovery["payload"]["name"].asText())
                assertTrue(discovery["seq"].isNull)
                assertTrue(discovery["threadId"].asText() != execution.toString())
                respond(socket, discovery, """{"tools":[$definition]}""")
                val invoked = readJson(socket)
                assertEquals("speaker.say", invoked["payload"]["name"].asText())
                assertEquals(chat, invoked["chatId"].asText())
                assertTrue(invoked["seq"].isNull)
                assertTrue(invoked["threadId"].asText() != execution.toString())
                assertEquals(json.readTree("""{"text":"Same chat","volume":2}"""), invoked["payload"]["arguments"])
                respond(socket, invoked, """{"spoken":true}""")
                assertCompleted(user, chat, execution, "same chat tool completed")
            }
        }
    }

    @Test
    fun `malformed Orion catalogues reject all definitions without making tools callable`() {
        lateinit var target: String
        val llm = E2eLlmApi { request ->
            val results = request.currentResults()
            when (results.size) {
                0 -> toolCallReply(request, "RunSkillCommand", mapOf(
                    "skillId" to "orion.tools.list", "arguments" to mapOf("channelId" to target),
                ))
                1 -> {
                    assertEquals("invalid_client_tool_catalogue", restJsonMapper.readTree(results.last().content)["error"]["code"].asText())
                    toolCallReply(request, "GetSkillByName", mapOf("skillId" to "speaker.say"))
                }
                else -> {
                    assertEquals("skill_not_found", restJsonMapper.readTree(results.last().content)["error"]["code"].asText())
                    reply(request, "catalogue rejected atomically")
                }
            }
        }
        backendE2eTest("e2e_discovery_invalid", llm = llm) {
            val user = UUID.randomUUID().toString()
            val source = createPublicChat(user, "source")
            target = createPublicChat(user, "target")
            val invalid = listOf(
                "{}",
                """{"tools":null}""",
                """{"tools":[null]}""",
                """{"tools":[${definition.replace("\"Speak text\"", "123") }]}""",
                """{"tools":[${definition.replace("\"additionalProperties\":false", "\"additionalProperties\":\"false\"") }]}""",
                """{"tools":[$definition],"unexpected":true}""",
                """{"tools":[$definition,$definition]}""",
                """{"tools":[$definition,${definition.replace("speaker.say", "orion.tools.list")}]}""",
                """{"tools":[$definition,${definition.replace("speaker.say", "ReadFile")}]}""",
                """{"tools":[$definition,${definition.replace("speaker.say", "RunSkillCommand")}]}""",
                """{"tools":[$definition,${definition.replace("speaker.say", "bad.schema").replace("\"type\":\"integer\"", "\"type\":\"array\"")}]}""",
                """{"tools":[$definition,${definition.replace("speaker.say", "unsupported.schema").replace("\"type\":\"integer\"", "\"type\":\"integer\",\"minimum\":0")}]}""",
            )
            withMultiChatSocket { socket ->
                subscribe(socket, target)
                invalid.forEachIndexed { index, result ->
                    val execution = submitHttp(user, source, "invalid-$index")
                    val discovery = readJson(socket)
                    assertEquals("orion.tools.list", discovery["payload"]["name"].asText())
                    respond(socket, discovery, result)
                    assertCompleted(user, source, execution, "catalogue rejected atomically")
                }
            }
        }
    }

    @Test
    fun `invalid refresh preserves discovered tools and successful empty refresh clears them`() {
        lateinit var target: String
        val llm = E2eLlmApi { request ->
            val results = request.currentResults()
            fun discover() = toolCallReply(request, "RunSkillCommand", mapOf(
                "skillId" to "orion.tools.list", "arguments" to mapOf("channelId" to target),
            ))
            when (results.size) {
                0, 1 -> discover()
                2 -> {
                    assertEquals("invalid_client_tool_catalogue", restJsonMapper.readTree(results.last().content)["error"]["code"].asText())
                    toolCallReply(request, "GetSkillByName", mapOf("skillId" to "new.tool"))
                }
                3 -> {
                    assertEquals("skill_not_found", restJsonMapper.readTree(results.last().content)["error"]["code"].asText())
                    toolCallReply(request, "RunSkillCommand", mapOf(
                        "skillId" to "speaker.say", "arguments" to mapOf("text" to "Still available", "volume" to 2),
                    ))
                }
                4 -> {
                    assertTrue(restJsonMapper.readTree(results.last().content)["spoken"].asBoolean())
                    discover()
                }
                5 -> toolCallReply(request, "GetSkillByName", mapOf("skillId" to "speaker.say"))
                else -> {
                    assertEquals("skill_not_found", restJsonMapper.readTree(results.last().content)["error"]["code"].asText())
                    reply(request, "catalogue refreshed atomically")
                }
            }
        }
        backendE2eTest("e2e_discovery_refresh", llm = llm) {
            val user = UUID.randomUUID().toString()
            val source = createPublicChat(user, "source")
            target = createPublicChat(user, "target")
            withMultiChatSocket { socket ->
                subscribe(socket, target)
                val execution = submitHttp(user, source, "refresh")
                respond(socket, readJson(socket), """{"tools":[$definition]}""")
                respond(socket, readJson(socket), """{"tools":[${definition.replace("speaker.say", "new.tool")},${definition.replace("speaker.say", "orion.tools.list")}]}""")
                val invoked = readJson(socket)
                assertEquals("speaker.say", invoked["payload"]["name"].asText())
                assertEquals(target, invoked["chatId"].asText())
                respond(socket, invoked, """{"spoken":true}""")
                val emptyRefresh = readJson(socket)
                assertEquals("orion.tools.list", emptyRefresh["payload"]["name"].asText())
                respond(socket, emptyRefresh, """{"tools":[]}""")
                assertCompleted(user, source, execution, "catalogue refreshed atomically")
            }
        }
    }

    private suspend fun BackendE2eScope.subscribe(socket: DefaultClientWebSocketSession, chatId: String) {
        socket.send(Frame.Text("""{"kind":"chat.subscribe","chatId":"$chatId","requestId":"subscribe-$chatId"}"""))
        assertEquals("accepted", readJson(socket)["status"].asText())
    }

    private suspend fun BackendE2eScope.respond(socket: DefaultClientWebSocketSession, call: JsonNode, result: String) {
        socket.send(Frame.Text("""{"kind":"tool.result","chatId":${call["chatId"]},"threadId":${call["threadId"]},"toolCallId":${call["payload"]["toolCallId"]},"status":"succeeded","result":$result}"""))
        assertEquals("accepted", readJson(socket)["status"].asText())
    }

    private suspend fun BackendE2eScope.submitHttp(userId: String, chatId: String, prompt: String): UUID {
        val sent = client.post(BackendHttpRoutes.chatMessages(chatId)) {
            trusted(userId)
            jsonBody("""{"content":"$prompt","options":{"model":"${E2E_LOCAL_MODEL.alias}"}}""")
        }
        assertEquals(HttpStatusCode.OK, sent.status)
        return UUID.fromString(sent.jsonBody()["execution"]["id"].asText())
    }

    private suspend fun BackendE2eScope.assertCompleted(userId: String, chatId: String, execution: UUID, answer: String) {
        backend.awaitExecution(execution)
        val messages = client.get(BackendHttpRoutes.chatMessages(chatId)) { trusted(userId) }.jsonBody()["items"]
        assertEquals(answer, messages.last()["content"].asText())
    }
}

private fun LLMRequest.Chat.currentResults(): List<LLMRequest.Message> =
    messages.drop(messages.indexOfLast { it.role == LLMMessageRole.user }.coerceAtLeast(0))
        .filter { it.role == LLMMessageRole.function }
