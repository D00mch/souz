package ru.souz.backend.e2e

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.restJsonMapper

class BackendSubagentChannelE2eTest {
    @Test
    fun `children route closed-schema client tools with parent context or selected channel discovery`() {
        for (http in listOf(false, true)) {
            lateinit var target: String
            backendE2eTest("e2e_child_channel", llm = E2eLlmApi { request ->
                val result = request.messages.lastOrNull { it.role == LLMMessageRole.function }
                if (request.functions.any { it.name == "speaker.say" }) {
                    val clientTool = request.functions.single { it.name == "speaker.say" }
                    assertEquals("Speak text", clientTool.description)
                    assertEquals(false, clientTool.parameters.additionalProperties)
                    assertEquals(listOf("text"), clientTool.parameters.required)
                    val routing = clientTool.parameters.properties.getValue("channelId")
                    assertEquals("string", routing.type)
                    assertTrue("current client" in routing.description.orEmpty())
                    assertTrue("ListActiveChannels" in routing.description.orEmpty())
                    assertTrue("parent" in routing.description.orEmpty())
                    assertEquals(if (http) setOf("speaker.say", "ListActiveChannels") else setOf("speaker.say"),
                        request.functions.map { it.name }.toSet())
                    when {
                        result?.name == "speaker.say" -> {
                            assertTrue(restJsonMapper.readTree(result.content)["spoken"].asBoolean())
                            reply(request, "child done")
                        }
                        http && result == null -> {
                            val channels = request.functions.single { it.name == "ListActiveChannels" }
                            assertFalse("channelId" in channels.parameters.properties)
                            toolCallReply(request, "ListActiveChannels", emptyMap())
                        }
                        else -> {
                            val route = if (http) {
                                restJsonMapper.readTree(result!!.content)["channels"]
                                    .single { it["channelId"].asText() == target }["channelId"].asText()
                            } else request.conversationPrompt().substringAfter("channelId: ").also {
                                assertEquals(target, it)
                            }
                            toolCallReply(request, "speaker.say", mapOf("text" to "hello", "channelId" to route))
                        }
                    }
                } else if (result?.name == "SpawnSubagent") {
                    assertEquals("child done", restJsonMapper.readTree(result.content)["result"].asText())
                    reply(request, "parent done")
                } else {
                    toolCallReply(request, "SpawnSubagent", mapOf(
                        "task" to if (http) "Say hello on the connected target device." else "Say hello using channelId: $target",
                        "skillIds" to if (http) listOf("speaker.say", "ListActiveChannels") else listOf("speaker.say"),
                    ))
                }
            }) {
                val owner = UUID.randomUUID().toString()
                target = createPublicChat(owner, "target")
                val source = createPublicChat(owner, "source", tools = """[{"name":"speaker.say","description":"Speak text","parameters":{"type":"object","properties":{"text":{"type":"string"}},"required":["text"],"additionalProperties":false}}]""")
                withMultiChatSocket { socket ->
                    socket.send(Frame.Text("""{"kind":"chat.subscribe","chatId":"$target","requestId":"watch"}"""))
                    assertEquals("accepted", readJson(socket)["status"].asText())
                    if (http) {
                        assertEquals(HttpStatusCode.OK, client.post(BackendHttpRoutes.chatMessages(source)) {
                            trusted(owner)
                            jsonBody("""{"content":"Delegate","options":{"model":"${E2E_LOCAL_MODEL.alias}"}}""")
                        }.status)
                    } else {
                        socket.send(Frame.Text(messageFrame(source, owner, "submit", text = "Delegate", deviceId = "source-device")))
                        assertEquals("accepted", readJson(socket)["status"].asText())
                        assertEquals("thread.status", readJson(socket)["type"].asText())
                    }
                    val started = readJson(socket)
                    assertEquals("tool.call.started", started["type"].asText())
                    assertEquals(target, started["chatId"].asText())
                    assertTrue(started["seq"].isNull)
                    assertEquals("speaker.say", started["payload"]["name"].asText())
                    assertEquals(json.readTree("""{"text":"hello"}"""), started["payload"]["arguments"])
                    socket.send(Frame.Text("""{"kind":"tool.result","chatId":"$target","threadId":${started["threadId"]},"toolCallId":${started["payload"]["toolCallId"]},"status":"succeeded","result":{"spoken":true}}"""))
                    assertEquals("accepted", readJson(socket)["status"].asText())
                    if (!http) assertEquals("thread.completed", readJson(socket)["type"].asText())
                    eventually("parent receives routed child result") {
                        client.get(BackendHttpRoutes.chatMessages(source)) { trusted(owner) }.jsonBody()["items"]
                            .singleOrNull { it["content"].asText() == "parent done" }?.let { Unit }
                    }
                }
            }
        }
    }
}
