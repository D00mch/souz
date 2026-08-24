package ru.souz.backend.e2e

import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.llms.LLMMessageRole

class BackendChannelDeliveryE2eTest {
    @Test
    fun `public channel delivery emits an out of band event and consumes an active turn gap once`() {
        val activePrompt = "active target turn"
        val deliveryPrompt = "deliver while target is active"
        val deliveredText = "cross-channel delivery"
        val llm = E2eLlmApi().apply {
            pausePromptUntilReleased(activePrompt)
        }
        backendE2eTest(
            schemaPrefix = "e2e_channel_delivery",
            llm = llm,
        ) {
            val userId = UUID.randomUUID().toString()
            val targetChatId = createPublicChat(userId, "create-target")
            val sourceChatId = createPublicChat(userId, "create-source")
            llm.requestSkillForPrompt(
                prompt = deliveryPrompt,
                skillId = "SendMessageToChannel",
                arguments = mapOf(
                    "channelType" to "public_client",
                    "channelId" to targetChatId,
                    "text" to deliveredText,
                ),
            )

            coroutineScope {
                val activeTurn = async { sendTurn(targetChatId, userId, activePrompt) }
                llm.awaitPrompt(activePrompt)
                val wsClient = webSocketClient()
                val session = wsClient.webSocketSession(
                    "${BackendHttpRoutes.chatWebSocket(targetChatId)}?clientType=backend"
                )

                val delivery = sendTurn(sourceChatId, userId, deliveryPrompt)
                assertEquals(HttpStatusCode.OK, delivery.status)
                val pushed = withTimeout(5_000) {
                    json.readTree((session.incoming.receive() as Frame.Text).readText())
                }
                assertEquals("event", pushed["kind"].asText())
                assertEquals("message.created", pushed["type"].asText())
                assertTrue(pushed["threadId"].isNull)
                assertEquals(deliveredText, pushed["payload"]["content"].asText())

                llm.releasePrompt(activePrompt)
                assertEquals(HttpStatusCode.OK, activeTurn.await().status)
                session.close()
                wsClient.close()
            }
            awaitTerminalCount(targetChatId, userId, 1)

            val durablePushes = client.get(BackendHttpRoutes.chatEvents(targetChatId)) {
                trusted(userId)
            }.jsonBody()["items"].filter { event ->
                event["type"].asText() == "message.created" &&
                    event["executionId"].isNull &&
                    event["payload"]["content"].asText() == deliveredText
            }
            assertEquals(1, durablePushes.size)

            assertEquals(HttpStatusCode.OK, sendTurn(targetChatId, userId, "consume gap").status)
            awaitTerminalCount(targetChatId, userId, 2)
            assertEquals(HttpStatusCode.OK, sendTurn(targetChatId, userId, "consume gap again").status)
            awaitTerminalCount(targetChatId, userId, 3)
            listOf("consume gap", "consume gap again").forEach { prompt ->
                val request = llm.requests.last { it.conversationPrompt() == prompt }
                assertEquals(
                    1,
                    request.messages.count { message ->
                        message.role == LLMMessageRole.assistant && message.content == deliveredText
                    },
                    "The persisted cross-channel gap must appear exactly once for '$prompt'.",
                )
            }

            val messages = client.get(BackendHttpRoutes.chatMessages(targetChatId)) {
                trusted(userId)
            }.jsonBody()["items"]
            val persistedPushes = messages.filter { message ->
                message.path("metadata").path("crossChannel").asText() == "true"
            }
            assertEquals(1, persistedPushes.size)
            assertEquals(deliveredText, persistedPushes.single()["content"].asText())
        }
    }

    private suspend fun BackendE2eScope.createPublicChat(userId: String, requestId: String): String {
        val created = client.post(BackendHttpRoutes.CHATS) {
            jsonBody("""{"userId":"$userId","requestId":"$requestId","clientType":"backend"}""")
        }
        assertEquals(HttpStatusCode.Created, created.status)
        return created.jsonBody()["chat"]["id"].asText()
    }

    private suspend fun BackendE2eScope.sendTurn(chatId: String, userId: String, content: String) =
        client.post(BackendHttpRoutes.chatMessages(chatId)) {
            trusted(userId)
            jsonBody(
                """{"content":"$content","options":{"model":"${E2E_LOCAL_MODEL.alias}"}}"""
            )
        }

    private suspend fun BackendE2eScope.awaitTerminalCount(chatId: String, userId: String, count: Int) {
        eventually("$count terminal target turn(s)") {
            client.get(BackendHttpRoutes.chatEvents(chatId)) {
                trusted(userId)
            }.jsonBody()["items"].takeIf { events ->
                events.count { event ->
                    event["type"].asText() in setOf(
                        "execution.finished",
                        "execution.failed",
                        "execution.cancelled",
                    )
                } == count
            }
        }
    }
}
