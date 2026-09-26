package ru.souz.backend.e2e

import io.ktor.client.request.get
import io.ktor.websocket.Frame
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import ru.souz.backend.http.BackendHttpRoutes

class BackendAssistantMessageE2eTest {
    @Test
    fun `both socket endpoints deliver progress after ack without receipts or replay`() {
        val releaseChild = CompletableDeferred<Unit>()
        val blocks = listOf("Let me check.", "One more detail.", "Let me check.")
        backendE2eTest("e2e_assistant_blocks", llm = E2eLlmApi { request ->
            when {
                request.functions.none { it.name == "SpawnSubagent" } -> {
                    releaseChild.await()
                    reply(request, "private child answer")
                }
                request.messages.any { it.name == "SpawnSubagent" } -> reply(request, "final answer")
                else -> {
                    val tool = toolCallReply(request, "SpawnSubagent", mapOf("task" to "child work"))
                    tool.copy(choices = (blocks.flatMap { reply(request, it).choices } + tool.choices)
                        .mapIndexed { index, choice -> choice.copy(index = index) })
                }
            }
        }) {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            withPublicSocket(chatId) { primary ->
                withMultiChatSocket { observer ->
                    try {
                        observer.send(Frame.Text("""{"kind":"chat.subscribe","chatId":"$chatId","requestId":"watch"}"""))
                        assertEquals("accepted", readJson(observer)["status"].asText())
                        primary.send(Frame.Text(messageFrame(chatId, userId, "start", text = "delegate")))
                        val ack = readJson(primary)
                        assertEquals("accepted", ack["status"].asText())
                        assertEquals("thread.status", readJson(primary)["type"].asText())

                        // Tool execution proceeds before either subscriber reads progress.
                        llm.awaitPrompt("child work")
                        for (socket in listOf(primary, observer)) for (content in blocks) {
                            val event = readJson(socket)
                            assertEquals("assistant.message", event["type"].asText())
                            assertEquals(chatId, event["chatId"].asText())
                            assertEquals(ack["thread"]["id"], event["threadId"])
                            assertTrue(event["seq"].isNull)
                            assertEquals(json.createObjectNode().put("content", content), event["payload"])
                        }
                        releaseChild.complete(Unit)
                        val terminal = readJson(primary)
                        assertEquals("thread.completed", terminal["type"].asText())
                        assertEquals("final answer", terminal["payload"]["response"].asText())
                        assertEquals(terminal, readJson(observer))

                        withPublicSocket(chatId) { replay ->
                            assertEquals(terminal, readJson(replay))
                            replay.send(Frame.Text(historyFrame(chatId, "history", "user", "passive context")))
                            assertEquals("ack", readJson(replay)["kind"].asText())
                        }
                        val stored = client.get(BackendHttpRoutes.chatEvents(chatId)) { trusted(userId) }.jsonBody()["items"]
                        assertEquals(listOf("thread.completed"), stored.map { it["type"].asText() })
                        val messages = client.get(BackendHttpRoutes.chatMessages(chatId)) { trusted(userId) }.jsonBody()["items"]
                        assertEquals(listOf("delegate", "final answer", "passive context"), messages.map { it["content"].asText() })
                    } finally {
                        releaseChild.complete(Unit)
                    }
                }
            }
        }
    }
}
