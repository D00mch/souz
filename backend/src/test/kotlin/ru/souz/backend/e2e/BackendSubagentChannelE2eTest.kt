package ru.souz.backend.e2e

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.restJsonMapper

class BackendSubagentChannelE2eTest {
    @Test
    fun `HTTP child routes a closed-schema client tool using the parent supplied channel`() {
        lateinit var target: String
        backendE2eTest("e2e_child_channel", llm = E2eLlmApi { request ->
            val result = request.messages.lastOrNull { it.role == LLMMessageRole.function }
            if (request.functions.any { it.name == "speaker.say" }) {
                assertEquals(listOf("speaker.say"), request.functions.map { it.name })
                if (result != null) {
                    assertEquals("speaker.say", result.name)
                    assertTrue(restJsonMapper.readTree(result.content)["spoken"].asBoolean())
                    reply(request, "child done")
                } else {
                    val route = request.conversationPrompt().substringAfter("channelId: ")
                    assertEquals(target, route)
                    toolCallReply(request, "speaker.say", mapOf("text" to "hello", "channelId" to route))
                }
            } else if (result?.name == "SpawnSubagent") {
                assertEquals("child done", restJsonMapper.readTree(result.content)["result"].asText())
                reply(request, "parent done")
            } else {
                toolCallReply(request, "SpawnSubagent", mapOf(
                    "task" to "Say hello using channelId: $target", "skillIds" to listOf("speaker.say"),
                ))
            }
        }) {
            val owner = UUID.randomUUID().toString()
            target = createPublicChat(owner, "target")
            val source = createPublicChat(owner, "source", tools = """[{"name":"speaker.say","description":"Speak text","parameters":{"type":"object","properties":{"text":{"type":"string"}},"required":["text"],"additionalProperties":false}}]""")
            withMultiChatSocket { socket ->
                socket.send(Frame.Text("""{"kind":"chat.subscribe","chatId":"$target","requestId":"watch"}"""))
                assertEquals("accepted", readJson(socket)["status"].asText())
                assertEquals(HttpStatusCode.OK, client.post(BackendHttpRoutes.chatMessages(source)) {
                    trusted(owner)
                    jsonBody("""{"content":"Delegate","options":{"model":"${E2E_LOCAL_MODEL.alias}"}}""")
                }.status)
                val started = readJson(socket)
                assertEquals("tool.call.started", started["type"].asText())
                assertEquals(target, started["chatId"].asText())
                assertTrue(started["seq"].isNull)
                assertEquals("speaker.say", started["payload"]["name"].asText())
                assertEquals(json.readTree("""{"text":"hello"}"""), started["payload"]["arguments"])
                socket.send(Frame.Text("""{"kind":"tool.result","chatId":"$target","threadId":${started["threadId"]},"toolCallId":${started["payload"]["toolCallId"]},"status":"succeeded","result":{"spoken":true}}"""))
                assertEquals("accepted", readJson(socket)["status"].asText())
                eventually("parent receives routed child result") {
                    client.get(BackendHttpRoutes.chatMessages(source)) { trusted(owner) }.jsonBody()["items"]
                        .singleOrNull { it["content"].asText() == "parent done" }?.let { Unit }
                }
            }
        }
    }
}
