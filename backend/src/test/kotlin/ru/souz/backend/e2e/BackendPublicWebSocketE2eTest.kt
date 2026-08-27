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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.storage.postgres.newPostgresSchema

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

    @Test
    fun `concurrent socket retries execute once and return the same thread`() =
        backendE2eTest("e2e_ws_retry", llm = E2eLlmApi().apply { pauseUntilReleased() }) {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            val wsClient = webSocketClient()
            val sessions = listOf(
                wsClient.webSocketSession("${BackendHttpRoutes.chatWebSocket(chatId)}?clientType=backend"),
                wsClient.webSocketSession("${BackendHttpRoutes.chatWebSocket(chatId)}?clientType=backend"),
            )
            val raw = messageFrame(chatId, userId, "message-retry", null, "execute once", "device-1")

            coroutineScope {
                val responses = sessions.map { session ->
                    async {
                        session.send(Frame.Text(raw))
                        readJson(session) to readJson(session)
                    }
                }.awaitAll()
                val acknowledgements = responses.map { it.first }
                val statuses = responses.map { it.second }

                assertEquals(listOf(false, true), acknowledgements.map { it["duplicate"].asBoolean() }.sorted())
                assertEquals(1, acknowledgements.map { it["thread"]["id"].asText() }.distinct().size)
                assertEquals(1, statuses.map { it["threadId"].asText() }.distinct().size)
                llm.awaitPrompt("execute once")
                assertEquals(1, llm.requests.size)
                val messages = client.get(BackendHttpRoutes.chatMessages(chatId)) {
                    trusted(userId)
                }.jsonBody()["items"]
                assertEquals(1, messages.count { it["role"].asText() == "user" })

                llm.release()
                assertEquals("thread.completed", readJson(sessions.first())["type"].asText())
            }
            sessions.forEach { it.close() }
            wsClient.close()
        }

    @Test
    fun `client tool result is acknowledged idempotently before the thread completes`() =
        backendE2eTest(
            schemaPrefix = "e2e_ws_client_tool",
            llm = E2eLlmApi().apply {
                requestSkill("user.ask", mapOf("question" to "Which genre?"))
            },
        ) {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            val wsClient = webSocketClient()
            val session = wsClient.webSocketSession("${BackendHttpRoutes.chatWebSocket(chatId)}?clientType=backend")

            session.send(Frame.Text(messageFrame(chatId, userId, "message-tool", null, "ask me", "device-tool")))
            val messageAck = readJson(session)
            readJson(session)
            val started = readJson(session)
            val threadId = messageAck["thread"]["id"].asText()
            val toolCallId = started["payload"]["toolCallId"].asText()

            assertEquals("tool.call.started", started["type"].asText())
            assertEquals("user.ask", started["payload"]["name"].asText())
            assertEquals("device-tool", started["payload"]["deviceId"].asText())
            assertEquals("Which genre?", started["payload"]["arguments"]["question"].asText())

            val resultFrame =
                """{"kind":"tool.result","chatId":"$chatId","threadId":"$threadId","toolCallId":"$toolCallId","status":"succeeded","result":{"answer":"Horror"}}"""
            session.send(Frame.Text(resultFrame))
            val accepted = readJson(session)
            val terminal = readJson(session)
            assertEquals("accepted", accepted["status"].asText())
            assertFalse(accepted["duplicate"].asBoolean())
            assertEquals("thread.completed", terminal["type"].asText())

            session.send(Frame.Text(resultFrame))
            val duplicate = readJson(session)
            assertEquals("accepted", duplicate["status"].asText())
            assertTrue(duplicate["duplicate"].asBoolean())

            session.send(
                Frame.Text(
                    """{"kind":"tool.result","chatId":"$chatId","threadId":"$threadId","toolCallId":"$toolCallId","status":"succeeded","result":{"answer":"Comedy"}}"""
                )
            )
            val conflict = readJson(session)
            assertEquals("rejected", conflict["status"].asText())
            assertEquals("idempotency_conflict", conflict["error"]["code"].asText())

            session.close()
            wsClient.close()
        }

    @Test
    fun `client reported tool timeout completes once and accepts the same retry`() =
        backendE2eTest(
            schemaPrefix = "e2e_ws_client_tool_timeout",
            llm = E2eLlmApi().apply {
                requestSkill("user.ask", mapOf("question" to "Still there?"))
            },
        ) {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            val wsClient = webSocketClient()
            val session = wsClient.webSocketSession("${BackendHttpRoutes.chatWebSocket(chatId)}?clientType=backend")

            session.send(Frame.Text(messageFrame(chatId, userId, "message-timeout", null, "ask with timeout", "device-timeout")))
            val messageAck = readJson(session)
            readJson(session)
            val started = readJson(session)
            val threadId = messageAck["thread"]["id"].asText()
            val toolCallId = started["payload"]["toolCallId"].asText()
            val timeoutFrame =
                """{"kind":"tool.result","chatId":"$chatId","threadId":"$threadId","toolCallId":"$toolCallId","status":"timed_out","error":{"code":"client_tool_timed_out","message":"Device deadline expired."}}"""

            session.send(Frame.Text(timeoutFrame))
            val accepted = readJson(session)
            val terminal = readJson(session)
            assertEquals("accepted", accepted["status"].asText())
            assertFalse(accepted["duplicate"].asBoolean())
            assertEquals("thread.completed", terminal["type"].asText())

            session.send(Frame.Text(timeoutFrame))
            val duplicate = readJson(session)
            assertEquals("accepted", duplicate["status"].asText())
            assertTrue(duplicate["duplicate"].asBoolean())

            session.close()
            wsClient.close()
        }

    @Test
    fun `device mcp list_devices reaches the client device and returns discovered devices`() =
        backendE2eTest(
            schemaPrefix = "e2e_ws_device_mcp_list_devices",
            llm = E2eLlmApi().apply {
                requestSkill("device.mcp.list_devices", emptyMap())
            },
        ) {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            val wsClient = webSocketClient()
            val session = wsClient.webSocketSession("${BackendHttpRoutes.chatWebSocket(chatId)}?clientType=backend")

            session.send(Frame.Text(messageFrame(chatId, userId, "message-mcp-devices", null, "list mcp devices", "device-mcp")))
            val messageAck = readJson(session)
            readJson(session)
            val started = readJson(session)
            val threadId = messageAck["thread"]["id"].asText()
            val toolCallId = started["payload"]["toolCallId"].asText()

            assertEquals("tool.call.started", started["type"].asText())
            assertEquals("device.mcp.list_devices", started["payload"]["name"].asText())
            assertEquals("device-mcp", started["payload"]["deviceId"].asText())
            assertTrue(started["payload"]["arguments"].isEmpty)

            val resultFrame = """
                {"kind":"tool.result","chatId":"$chatId","threadId":"$threadId","toolCallId":"$toolCallId",
                 "status":"succeeded","result":{"devices":[{"id":"device-4471","name":"Кухонная станция","self":true}]}}
            """.trimIndent()
            session.send(Frame.Text(resultFrame))
            val accepted = readJson(session)
            val terminal = readJson(session)
            assertEquals("accepted", accepted["status"].asText())
            assertEquals("thread.completed", terminal["type"].asText())

            session.close()
            wsClient.close()
        }

    @Test
    fun `device mcp call_tool forwards the target tool name and arguments to the device`() =
        backendE2eTest(
            schemaPrefix = "e2e_ws_device_mcp_call_tool_success",
            llm = E2eLlmApi().apply {
                requestSkill("device.mcp.call_tool", mapOf("name" to "set_volume", "arguments" to mapOf("level" to 7)))
            },
        ) {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            val wsClient = webSocketClient()
            val session = wsClient.webSocketSession("${BackendHttpRoutes.chatWebSocket(chatId)}?clientType=backend")

            session.send(Frame.Text(messageFrame(chatId, userId, "message-mcp-call", null, "set the volume", "device-mcp")))
            val messageAck = readJson(session)
            readJson(session)
            val started = readJson(session)
            val threadId = messageAck["thread"]["id"].asText()
            val toolCallId = started["payload"]["toolCallId"].asText()

            assertEquals("device.mcp.call_tool", started["payload"]["name"].asText())
            assertEquals("set_volume", started["payload"]["arguments"]["name"].asText())
            assertEquals(7, started["payload"]["arguments"]["arguments"]["level"].asInt())

            val resultFrame =
                """{"kind":"tool.result","chatId":"$chatId","threadId":"$threadId","toolCallId":"$toolCallId","status":"succeeded","result":{"content":[{"type":"text","text":"Volume set to 7"}],"isError":false}}"""
            session.send(Frame.Text(resultFrame))
            val accepted = readJson(session)
            val terminal = readJson(session)
            assertEquals("accepted", accepted["status"].asText())
            assertEquals("thread.completed", terminal["type"].asText())

            session.close()
            wsClient.close()
        }

    @Test
    fun `device mcp call_tool completes normally when the MCP tool itself reports isError`() =
        backendE2eTest(
            schemaPrefix = "e2e_ws_device_mcp_call_tool_is_error",
            llm = E2eLlmApi().apply {
                requestSkill("device.mcp.call_tool", mapOf("name" to "set_volume", "arguments" to mapOf("level" to 500)))
            },
        ) {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            val wsClient = webSocketClient()
            val session = wsClient.webSocketSession("${BackendHttpRoutes.chatWebSocket(chatId)}?clientType=backend")

            session.send(Frame.Text(messageFrame(chatId, userId, "message-mcp-call-error", null, "set an invalid volume", "device-mcp")))
            val messageAck = readJson(session)
            readJson(session)
            val started = readJson(session)
            val threadId = messageAck["thread"]["id"].asText()
            val toolCallId = started["payload"]["toolCallId"].asText()

            val resultFrame =
                """{"kind":"tool.result","chatId":"$chatId","threadId":"$threadId","toolCallId":"$toolCallId","status":"succeeded","result":{"content":[{"type":"text","text":"level must be between 0 and 10"}],"isError":true}}"""
            session.send(Frame.Text(resultFrame))
            val accepted = readJson(session)
            val terminal = readJson(session)
            assertEquals("accepted", accepted["status"].asText())
            assertEquals("thread.completed", terminal["type"].asText())

            session.close()
            wsClient.close()
        }

    @Test
    fun `thread cancellation cancels a pending client tool and rejects a later result`() =
        backendE2eTest(
            schemaPrefix = "e2e_ws_client_tool_cancel",
            llm = E2eLlmApi().apply {
                requestSkill("user.ask", mapOf("question" to "Wait for me"))
            },
        ) {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            val wsClient = webSocketClient()
            val session = wsClient.webSocketSession("${BackendHttpRoutes.chatWebSocket(chatId)}?clientType=backend")

            session.send(Frame.Text(messageFrame(chatId, userId, "message-cancel-tool", null, "cancel pending tool", "device-cancel")))
            val messageAck = readJson(session)
            readJson(session)
            val started = readJson(session)
            val threadId = messageAck["thread"]["id"].asText()
            val toolCallId = started["payload"]["toolCallId"].asText()

            session.send(
                Frame.Text(
                    """{"kind":"thread.cancel","chatId":"$chatId","requestId":"cancel-tool","threadId":"$threadId","reason":"user_requested"}"""
                )
            )
            val cancelAck = readJson(session)
            val cancelStatus = readJson(session)
            val terminal = readJson(session)
            assertEquals("accepted", cancelAck["status"].asText())
            assertTrue(cancelStatus["status"].asText() in setOf("cancelling", "cancelled"))
            assertEquals("thread.cancelled", terminal["type"].asText())

            session.send(
                Frame.Text(
                    """{"kind":"tool.result","chatId":"$chatId","threadId":"$threadId","toolCallId":"$toolCallId","status":"succeeded","result":{"answer":"too late"}}"""
                )
            )
            val rejected = readJson(session)
            assertEquals("rejected", rejected["status"].asText())
            assertEquals("idempotency_conflict", rejected["error"]["code"].asText())

            session.close()
            wsClient.close()
        }

    @Test
    fun `expired thread lease is failed and replayed once after restart`() {
        val schema = newPostgresSchema("e2e_ws_recovery")
        val userId = UUID.randomUUID().toString()
        lateinit var chatId: String
        lateinit var threadId: String

        backendE2eTest(
            schemaPrefix = "e2e_ws_recovery_running",
            schema = schema,
            llm = E2eLlmApi().apply { hangUntilCancelled() },
        ) {
            chatId = createPublicChat(userId)
            val wsClient = webSocketClient()
            val session = wsClient.webSocketSession("${BackendHttpRoutes.chatWebSocket(chatId)}?clientType=backend")
            session.send(Frame.Text(messageFrame(chatId, userId, "message-crash", null, "crash me", "device-crash")))
            val acknowledgement = readJson(session)
            readJson(session)
            threadId = acknowledgement["thread"]["id"].asText()
            llm.awaitPrompt("crash me")
            session.close()
            wsClient.close()
        }

        backendE2eTest(
            schemaPrefix = "e2e_ws_recovery_crash_fixture",
            schema = schema,
        ) {
            sql { connection ->
                connection.prepareStatement(
                    """
                    update agent_executions
                    set status = 'running',
                        finished_at = null,
                        cancel_requested = false,
                        error_code = null,
                        error_message = null,
                        runtime_owner = 'crashed-instance',
                        runtime_lease_until = current_timestamp - interval '1 minute'
                    where id = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setObject(1, UUID.fromString(threadId))
                    assertEquals(1, statement.executeUpdate())
                }
                connection.prepareStatement(
                    """
                    delete from agent_events
                    where execution_id = ?
                      and type in (
                        'execution.finished', 'execution.failed', 'execution.cancelled',
                        'thread.completed', 'thread.failed', 'thread.cancelled'
                      )
                    """.trimIndent()
                ).use { statement ->
                    statement.setObject(1, UUID.fromString(threadId))
                    statement.executeUpdate()
                }
            }
        }

        backendE2eTest(
            schemaPrefix = "e2e_ws_recovery_restarted",
            schema = schema,
            startBackgroundServices = true,
        ) {
            val status = client.get("${BackendHttpRoutes.chatThread(chatId, threadId)}?clientType=backend")
            assertEquals(HttpStatusCode.OK, status.status)
            assertEquals("failed", status.jsonBody()["status"].asText())
            assertFalse(status.jsonBody()["alive"].asBoolean())
            assertEquals("internal_error", status.jsonBody()["error"]["code"].asText())

            val wsClient = webSocketClient()
            val replay = wsClient.webSocketSession(
                "${BackendHttpRoutes.chatWebSocket(chatId)}?clientType=backend&afterSeq=0"
            )
            val recovered = readJson(replay)
            assertEquals("thread.failed", recovered["type"].asText())
            assertEquals(threadId, recovered["threadId"].asText())
            assertEquals("internal_error", recovered["payload"]["error"]["code"].asText())
            replay.close()
            wsClient.close()

            val durable = client.get(BackendHttpRoutes.chatEvents(chatId)) {
                trusted(userId)
            }.jsonBody()["items"]
            assertEquals(1, durable.count { it["type"].asText() == "thread.failed" })
        }
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
