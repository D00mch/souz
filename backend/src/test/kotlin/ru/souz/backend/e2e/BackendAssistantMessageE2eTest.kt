package ru.souz.backend.e2e

import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.llms.LLMMessageRole

class BackendAssistantMessageE2eTest {
    @Test
    fun `accepted blocks reach both subscribers after ack without receipts or durable history`() {
        for (streaming in listOf(false, true)) for (multiChat in listOf(false, true)) {
            val releaseChild = CompletableDeferred<Unit>()
            val blocks = listOf("Let me check.", "One more detail.", "Let me check.")
            backendE2eTest(
                schemaPrefix = "e2e_assistant_blocks",
                featureFlags = BackendFeatureFlags(wsEvents = true, streamingMessages = streaming),
                llm = E2eLlmApi { request ->
                    when {
                        request.functions.none { it.name == "SpawnSubagent" } -> {
                            releaseChild.await()
                            reply(request, "private child answer")
                        }
                        request.conversationPrompt() == "follow-up" -> reply(request, "next answer")
                        request.messages.any { it.name == "SpawnSubagent" } -> reply(request, "final answer")
                        else -> {
                            val tool = toolCallReply(request, "SpawnSubagent", mapOf("task" to "child work"))
                            tool.copy(choices = (blocks.flatMap { reply(request, it).choices } + tool.choices)
                                .mapIndexed { index, choice -> choice.copy(index = index) })
                        }
                    }
                },
            ) {
                val userId = UUID.randomUUID().toString()
                val chatId = createPublicChat(userId)
                val otherUser = UUID.randomUUID().toString()
                val otherChat = createPublicChat(otherUser)
                assertEquals(io.ktor.http.HttpStatusCode.OK, client.patch(BackendHttpRoutes.SETTINGS) {
                    trusted(userId)
                    jsonBody("""{"streamingMessages":$streaming}""")
                }.status)
                webSocketClient().use { sockets ->
                    val url = if (multiChat) BackendHttpRoutes.WS else BackendHttpRoutes.chatWebSocket(chatId)
                    val primary = sockets.webSocketSession("$url?clientType=backend")
                    val observer = sockets.webSocketSession("${BackendHttpRoutes.WS}?clientType=backend")
                    val unrelated = sockets.webSocketSession("${BackendHttpRoutes.WS}?clientType=backend")
                    try {
                        unrelated.send(Frame.Text("""{"kind":"chat.subscribe","chatId":"$otherChat","requestId":"watch-other"}"""))
                        assertEquals("accepted", readJson(unrelated)["status"].asText())
                        observer.send(Frame.Text("""{"kind":"chat.subscribe","chatId":"$chatId","requestId":"watch"}"""))
                        assertEquals("accepted", readJson(observer)["status"].asText())
                        primary.send(Frame.Text(messageFrame(chatId, userId, "start", text = "delegate")))
                        val ack = readJson(primary)
                        assertEquals("ack", ack["kind"].asText())
                        assertEquals("accepted", ack["status"].asText())
                        assertEquals("thread.status", readJson(primary)["type"].asText())
                        val threadId = ack["thread"]["id"].asText()

                        // The tool is already executing without any receipt for progress.
                        llm.awaitPrompt("child work")
                        for (socket in listOf(primary, observer)) for (content in blocks) {
                            val event = readJson(socket)
                            assertEquals("assistant.message", event["type"].asText())
                            assertEquals("event", event["kind"].asText())
                            assertEquals(chatId, event["chatId"].asText())
                            assertEquals(threadId, event["threadId"].asText())
                            assertTrue(event["seq"].isNull)
                            assertEquals(setOf("content"), event["payload"].fieldNames().asSequence().toSet())
                            assertEquals(content, event["payload"]["content"].asText())
                            assertEquals(setOf("kind", "seq", "type", "chatId", "threadId", "payload", "createdAt"),
                                event.fieldNames().asSequence().toSet())
                        }

                        // An unrelated chat must not receive either subscriber's progress.
                        unrelated.send(Frame.Text(messageFrame(otherChat, otherUser, "other", text = "follow-up")))
                        assertEquals("ack", readJson(unrelated)["kind"].asText())
                        assertEquals("thread.status", readJson(unrelated)["type"].asText())
                        val completed = readJson(unrelated)
                        assertEquals("thread.completed", completed["type"].asText())
                        assertEquals(otherChat, completed["chatId"].asText())

                        releaseChild.complete(Unit)
                        val terminal = readJson(primary)
                        assertEquals("thread.completed", terminal["type"].asText())
                        assertEquals("final answer", terminal["payload"]["response"].asText())
                        assertEquals(terminal, readJson(observer))

                        withPublicSocket(chatId) { replay ->
                            assertEquals(terminal, readJson(replay))
                            // This ACK is a barrier: no progress may trail the replayed terminal.
                            replay.send(Frame.Text(historyFrame(chatId, "history", "user", "passive context")))
                            assertEquals("ack", readJson(replay)["kind"].asText())
                        }
                        val stored = client.get(BackendHttpRoutes.chatEvents(chatId)) { trusted(userId) }.jsonBody()["items"]
                        assertEquals(listOf("thread.completed"), stored.map { it["type"].asText() })
                        val messages = client.get(BackendHttpRoutes.chatMessages(chatId)) { trusted(userId) }.jsonBody()["items"]
                        assertEquals(listOf("delegate", "final answer", "passive context"), messages.map { it["content"].asText() })

                        primary.send(Frame.Text(messageFrame(chatId, userId, "next", text = "follow-up")))
                        assertEquals("ack", readJson(primary)["kind"].asText())
                        assertEquals("thread.status", readJson(primary)["type"].asText())
                        assertEquals("thread.completed", readJson(primary)["type"].asText())
                        val history = llm.requests.last().messages
                        assertEquals(blocks, history.filter { it.role == LLMMessageRole.assistant && it.content in blocks }.map { it.content })
                        assertFalse(history.any { it.role == LLMMessageRole.assistant && it.content == "private child answer" })
                    } finally {
                        releaseChild.complete(Unit)
                        primary.close()
                        observer.close()
                        unrelated.close()
                    }
                }
            }
        }
    }
}
