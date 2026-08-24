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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import ru.souz.backend.http.BackendHttpRoutes

class BackendPublicWebSocketE2eTest {
    @Test
    fun `public socket acknowledges before status and terminal event and replays durable terminal`() =
        backendE2eTest("e2e_ws_ordering") {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            val wsClient = webSocketClient()
            val session = wsClient.webSocketSession("${BackendHttpRoutes.chatWebSocket(chatId)}?clientType=backend")

            session.send(Frame.Text(messageFrame(chatId, userId, "message-1", null, "hello socket", "device-1")))
            val ack = readJson(session)
            val status = readJson(session)
            val terminal = readJson(session)
            val threadId = ack["thread"]["id"].asText()

            assertEquals("ack", ack["kind"].asText())
            assertEquals("accepted", ack["status"].asText())
            assertEquals("status", status["kind"].asText())
            assertEquals("thread.status", status["type"].asText())
            assertEquals(threadId, status["threadId"].asText())
            assertEquals("event", terminal["kind"].asText())
            assertEquals("thread.completed", terminal["type"].asText())
            assertEquals(threadId, terminal["threadId"].asText())

            val queried = client.get("${BackendHttpRoutes.chatThread(chatId, threadId)}?clientType=backend")
            assertEquals(HttpStatusCode.OK, queried.status)
            assertEquals("completed", queried.jsonBody()["status"].asText())
            assertFalse(queried.jsonBody()["alive"].asBoolean())

            session.send(Frame.Text(messageFrame(chatId, userId, "message-too-late", threadId, "late", "device-1")))
            val rejected = readJson(session)
            assertEquals("rejected", rejected["status"].asText())
            assertEquals("thread_already_terminal", rejected["error"]["code"].asText())
            session.close()

            val replay = wsClient.webSocketSession("${BackendHttpRoutes.chatWebSocket(chatId)}?clientType=backend&afterSeq=0")
            val replayedTerminal = readJson(replay)
            assertEquals(terminal["seq"].asLong(), replayedTerminal["seq"].asLong())
            assertEquals("thread.completed", replayedTerminal["type"].asText())
            replay.close()
            wsClient.close()
        }

    @Test
    fun `running socket thread accepts second input and preserves revision ordering`() =
        backendE2eTest("e2e_ws_second_input", llm = E2eLlmApi().apply { pauseUntilReleased() }) {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            val wsClient = webSocketClient()
            val session = wsClient.webSocketSession("${BackendHttpRoutes.chatWebSocket(chatId)}?clientType=backend")

            session.send(Frame.Text(messageFrame(chatId, userId, "message-1", null, "first", "device-1")))
            val firstAck = readJson(session)
            val firstStatus = readJson(session)
            val threadId = firstAck["thread"]["id"].asText()
            assertEquals(threadId, firstStatus["threadId"].asText())
            llm.awaitPrompt("first")

            session.send(Frame.Text(messageFrame(chatId, userId, "message-2", threadId, "second", "device-2")))
            val secondAck = readJson(session)
            val secondStatus = readJson(session)
            llm.release()
            val terminal = readJson(session)

            assertEquals(2, secondAck["submission"]["inputSeq"].asInt())
            assertEquals(2, secondAck["thread"]["revision"].asInt())
            assertFalse(secondAck["thread"]["created"].asBoolean())
            assertEquals(2, secondStatus["revision"].asInt())
            assertEquals("thread.completed", terminal["type"].asText())
            val messages = client.get(BackendHttpRoutes.chatMessages(chatId)) {
                trusted(userId)
            }.jsonBody()["items"]
            assertEquals(listOf("first", "second"), messages.filter { it["role"].asText() == "user" }.map { it["content"].asText() })
            session.close()
            wsClient.close()
        }

    @Test
    fun `socket cancellation acknowledgement precedes cancelled terminal event`() =
        backendE2eTest("e2e_ws_cancel", llm = E2eLlmApi().apply { hangUntilCancelled() }) {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            val wsClient = webSocketClient()
            val session = wsClient.webSocketSession("${BackendHttpRoutes.chatWebSocket(chatId)}?clientType=backend")

            coroutineScope {
                val reader = async {
                    session.send(Frame.Text(messageFrame(chatId, userId, "message-1", null, "cancel socket", "device-1")))
                    val messageAck = readJson(session)
                    val messageStatus = readJson(session)
                    messageAck to messageStatus
                }
                llm.awaitPrompt("cancel socket")
                val (messageAck, messageStatus) = reader.await()
                val threadId = messageAck["thread"]["id"].asText()
                assertEquals(threadId, messageStatus["threadId"].asText())

                session.send(
                    Frame.Text(
                        """{"kind":"thread.cancel","chatId":"$chatId","requestId":"cancel-1","threadId":"$threadId","reason":"user_requested"}"""
                    )
                )
                val cancelAck = readJson(session)
                val cancelStatus = readJson(session)
                val terminal = readJson(session)

                assertEquals("accepted", cancelAck["status"].asText())
                assertEquals("cancel-1", cancelAck["requestId"].asText())
                assertEquals(threadId, cancelStatus["threadId"].asText())
                assertEquals("thread.cancelled", terminal["type"].asText())
            }
            session.close()
            wsClient.close()
        }

    private suspend fun BackendE2eScope.createPublicChat(userId: String): String {
        val created = client.post(BackendHttpRoutes.CHATS) {
            jsonBody("""{"userId":"$userId","requestId":"create-1","clientType":"backend"}""")
        }
        assertEquals(HttpStatusCode.Created, created.status)
        return created.jsonBody()["chat"]["id"].asText()
    }

    private suspend fun BackendE2eScope.readJson(session: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession) =
        json.readTree((session.incoming.receive() as Frame.Text).readText())

    private fun messageFrame(
        chatId: String,
        userId: String,
        requestId: String,
        threadId: String?,
        text: String,
        deviceId: String,
    ): String =
        """
        {
          "kind": "message.submit",
          "chatId": "$chatId",
          "requestId": "$requestId",
          ${threadId?.let { "\"threadId\":\"$it\"," } ?: ""}
          "payload": {
            "device": {
              "userId": "$userId",
              "deviceId": "$deviceId",
              "deviceType": "tv_box",
              "capabilities": ["speech", "screen", "device_tools"]
            },
            "content": {
              "type": "text",
              "source": "voice",
              "text": "$text"
            },
            "meta": {
              "model": "${E2E_LOCAL_MODEL.alias}",
              "locale": "ru-RU",
              "timeZone": "Europe/Moscow"
            }
          }
        }
        """.trimIndent()
}
