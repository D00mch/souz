package ru.souz.backend.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import io.ktor.websocket.close
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
import ru.souz.llms.LLMMessageRole

class BackendPublicWebSocketE2eTest {
    private data class SocketNode(
        val scope: BackendE2eScope,
        val client: HttpClient,
        val session: DefaultClientWebSocketSession,
    )

    private data class SocketReply(val acknowledgement: JsonNode, val status: JsonNode?)

    @Test
    fun `public socket acknowledges before status and terminal event and replays durable terminal`() =
        backendE2eTest("e2e_ws_ordering") {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            val wsClient = webSocketClient()
            val session = wsClient.webSocketSession("${BackendHttpRoutes.chatWebSocket(chatId)}?clientType=backend")

            val initialFrame = messageFrame(chatId, userId, "message-1", null, "hello socket", "device-1")
            session.send(Frame.Text(initialFrame))
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
    fun `running socket thread accepts explicit and implicit follow ups`() =
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
            assertTrue(firstAck["thread"]["created"].asBoolean())
            llm.awaitPrompt("first")

            session.send(
                Frame.Text(
                    messageFrame(chatId, userId, "wrong-thread", UUID.randomUUID().toString(), "wrong", "device-1")
                )
            )
            assertEquals("thread_not_found", readJson(session)["error"]["code"].asText())

            session.send(Frame.Text(messageFrame(chatId, userId, "message-2", threadId, "second", "device-2")))
            val secondAck = readJson(session)
            val secondStatus = readJson(session)
            assertEquals(threadId, secondAck["thread"]["id"].asText())
            assertEquals(2, secondAck["submission"]["inputSeq"].asInt())
            assertEquals(2, secondAck["thread"]["revision"].asInt())
            assertFalse(secondAck["thread"]["created"].asBoolean())
            assertEquals(2, secondStatus["revision"].asInt())

            val implicitFollowUp = messageFrame(chatId, userId, "message-3", null, "third", "device-3")
            session.send(Frame.Text(implicitFollowUp))
            val thirdAck = readJson(session)
            val thirdStatus = readJson(session)
            assertEquals(threadId, thirdAck["thread"]["id"].asText())
            assertEquals(3, thirdAck["submission"]["inputSeq"].asInt())
            assertEquals(3, thirdAck["thread"]["revision"].asInt())
            assertFalse(thirdAck["thread"]["created"].asBoolean())
            assertEquals(3, thirdStatus["revision"].asInt())

            llm.release()
            val terminal = readJson(session)
            assertEquals("thread.completed", terminal["type"].asText())

            val messages = client.get(BackendHttpRoutes.chatMessages(chatId)) {
                trusted(userId)
            }.jsonBody()["items"]
            assertEquals(
                listOf("first", "second", "third"),
                messages.filter { it["role"].asText() == "user" }.map { it["content"].asText() },
            )
            session.close()
            wsClient.close()
        }

    @Test
    fun `cancellation acknowledgement precedes terminal and implicit input does not replace the thread`() {
        val llm = E2eLlmApi().apply { hangUntilCancellationReleased() }
        backendE2eTest("e2e_ws_cancel", llm = llm) {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            val wsClient = webSocketClient()
            val session = wsClient.webSocketSession("${BackendHttpRoutes.chatWebSocket(chatId)}?clientType=backend")

            try {
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

                    val cancelFrame =
                        """{"kind":"thread.cancel","chatId":"$chatId","requestId":"cancel-1","threadId":"$threadId","reason":"user_requested"}"""
                    session.send(Frame.Text(cancelFrame))
                    val cancelAck = readJson(session)
                    val cancelStatus = readJson(session)
                    assertEquals("accepted", cancelAck["status"].asText())
                    assertEquals("cancel-1", cancelAck["requestId"].asText())
                    assertEquals(threadId, cancelStatus["threadId"].asText())

                    session.send(Frame.Text(cancelFrame))
                    assertEquals(cancelAck.deepCopy<ObjectNode>().put("duplicate", true), readJson(session))
                    val duplicateStatus = readJson(session)
                    assertEquals("thread.status", duplicateStatus["type"].asText())
                    assertEquals("cancel-1", duplicateStatus["requestId"].asText())
                    assertEquals(threadId, duplicateStatus["threadId"].asText())

                    session.send(Frame.Text(messageFrame(chatId, userId, "message-2", null, "do not replace", "device-2")))
                    val rejected = readJson(session)
                    assertEquals("message_rejected", rejected["error"]["code"].asText())
                    assertTrue(rejected["thread"].isNull)
                    assertFalse(rejected.has("submission"))

                    val messages = client.get(BackendHttpRoutes.chatMessages(chatId)) {
                        trusted(userId)
                    }.jsonBody()["items"]
                    assertEquals(
                        listOf("cancel socket"),
                        messages.filter { it["role"].asText() == "user" }.map { it["content"].asText() },
                    )

                    llm.releaseCancellation()
                    assertEquals("thread.cancelled", readJson(session)["type"].asText())
                }
            } finally {
                llm.releaseCancellation()
                session.close()
                wsClient.close()
            }
        }
    }

    @Test
    fun `cross instance retries execute once and replay the original thread after completion`() {
        val primaryLlm = E2eLlmApi().apply { pauseUntilReleased() }
        val peerLlm = E2eLlmApi().apply { pauseUntilReleased() }
        backendE2eTest("e2e_ws_retry", llm = primaryLlm) {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            val raw = messageFrame(chatId, userId, "message-retry", null, "execute once", "device-1")
            try {
                withPeerSockets(chatId, peerLlm) { nodes ->
                    val responses = race(nodes, listOf(raw, raw))
                    val acknowledgements = responses.map { it.acknowledgement }
                    val threadId = acknowledgements.first()["thread"]["id"].asText()

                    assertEquals(listOf(false, true), acknowledgements.map { it["duplicate"].asBoolean() }.sorted())
                    assertTrue(acknowledgements.all { it["thread"]["created"].asBoolean() })
                    assertEquals(1, acknowledgements.map { it["thread"]["id"].asText() }.distinct().size)
                    assertEquals(1, acknowledgements.map { it["receivedAt"].asText() }.distinct().size)
                    assertEquals(1, responses.map { it.status?.get("threadId")?.asText() }.distinct().size)
                    eventually("one runtime request") {
                        (primaryLlm.requests.size + peerLlm.requests.size).takeIf { it == 1 }
                    }

                    val messages = client.get(BackendHttpRoutes.chatMessages(chatId)) {
                        trusted(userId)
                    }.jsonBody()["items"]
                    assertEquals(1, messages.count { it["role"].asText() == "user" })

                    primaryLlm.release()
                    peerLlm.release()
                    eventually("thread completion") { threadStatus(chatId, threadId).takeIf { it == "completed" } }

                    val losingNode = if (primaryLlm.requests.isEmpty()) 0 else 1
                    val duplicate = nodes[losingNode].request(raw)
                    val duplicateAck = duplicate.acknowledgement
                    assertEquals(acknowledgements.first().deepCopy<ObjectNode>().put("duplicate", true), duplicateAck)
                    assertEquals("completed", duplicate.status?.get("status")?.asText())
                }
            } finally {
                primaryLlm.release()
                peerLlm.release()
            }
        }
    }

    @Test
    fun `cross instance initial submissions reject the non owner and recover its history`() {
        val primaryLlm = E2eLlmApi().apply { pauseUntilReleased() }
        val peerLlm = E2eLlmApi().apply { pauseUntilReleased() }
        backendE2eTest("e2e_ws_initial_race", llm = primaryLlm) {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            try {
                withPeerSockets(chatId, peerLlm) { nodes ->
                    val frames = listOf(
                        messageFrame(chatId, userId, "message-a", null, "first contender", "device-a"),
                        messageFrame(chatId, userId, "message-b", null, "second contender", "device-b"),
                    )
                    val replies = race(nodes, frames)
                    val accepted = replies.single { it.acknowledgement["status"].asText() == "accepted" }.acknowledgement
                    val rejectedReply = replies.withIndex()
                        .single { it.value.acknowledgement["status"].asText() == "rejected" }
                    val rejected = rejectedReply.value.acknowledgement

                    assertTrue(accepted["thread"]["created"].asBoolean())
                    assertEquals("message_rejected", rejected["error"]["code"].asText())
                    eventually("one runtime request") {
                        (primaryLlm.requests.size + peerLlm.requests.size).takeIf { it == 1 }
                    }

                    val messages = client.get(BackendHttpRoutes.chatMessages(chatId)) {
                        trusted(userId)
                    }.jsonBody()["items"]
                    assertEquals(1, messages.count { it["role"].asText() == "user" })

                    val duplicate = nodes[1 - rejectedReply.index].request(frames[rejectedReply.index]).acknowledgement
                    assertEquals("rejected", duplicate["status"].asText())
                    assertTrue(duplicate["duplicate"].asBoolean())
                    assertEquals(rejected["error"], duplicate["error"])
                    assertEquals(rejected["receivedAt"], duplicate["receivedAt"])

                    val owner = nodes[1 - rejectedReply.index]
                    val nonOwner = nodes[rejectedReply.index]
                    nonOwner.session.send(
                        Frame.Text(historyFrame(chatId, "peer-history", "assistant", "completed by peer"))
                    )
                    val historyAck = nonOwner.scope.readJson(nonOwner.session)
                    assertEquals("accepted", historyAck["status"].asText())
                    assertFalse(historyAck.has("thread"))

                    val followUp = owner.request(
                        messageFrame(chatId, userId, "owner-follow-up", null, "owner follow up", "device-owner")
                    ).acknowledgement
                    assertEquals(accepted["thread"]["id"], followUp["thread"]["id"])
                    assertFalse(followUp["thread"]["created"].asBoolean())
                    owner.scope.llm.awaitPrompt("owner follow up")
                    val replacement = owner.scope.llm.requests.last().messages
                    assertEquals(
                        listOf(LLMMessageRole.assistant, LLMMessageRole.user),
                        replacement
                            .filter { it.content in setOf("completed by peer", "owner follow up") }
                            .map { it.role },
                    )
                    assertEquals(1, replacement.count { it.content == "completed by peer" })
                    assertTrue(nonOwner.scope.llm.requests.isEmpty())
                }
            } finally {
                primaryLlm.release()
                peerLlm.release()
            }
        }
    }

    @Test
    fun `cross instance submit cancel race applies only the stored winner`() {
        val llm = E2eLlmApi().apply { hangUntilCancellationReleased() }
        backendE2eTest("e2e_ws_submit_cancel_race", llm = llm) {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            try {
                withPeerSockets(chatId) { nodes ->
                    val initial = nodes.first().request(
                        messageFrame(chatId, userId, "initial", null, "stay active", "device-a")
                    ).acknowledgement
                    val threadId = initial["thread"]["id"].asText()
                    llm.awaitPrompt("stay active")
                    val (submit, cancel) = race(
                        listOf(nodes[1], nodes[0]),
                        listOf(
                            messageFrame(chatId, userId, "race", threadId, "losing input", "device-b"),
                            """{"kind":"thread.cancel","chatId":"$chatId","requestId":"race","threadId":"$threadId"}""",
                        ),
                    ).map { it.acknowledgement }

                    assertEquals(
                        1,
                        listOf(submit, cancel).count {
                            it.path("error").path("code").asText() == "idempotency_conflict"
                        },
                    )
                    if (cancel["status"].asText() == "accepted") {
                        assertEquals("idempotency_conflict", submit["error"]["code"].asText())
                        assertEquals("cancelling", threadStatus(chatId, threadId))
                    } else {
                        assertEquals("message_rejected", submit["error"]["code"].asText())
                        assertEquals("idempotency_conflict", cancel["error"]["code"].asText())
                        assertEquals("running", threadStatus(chatId, threadId))
                        val cleanup = nodes.first().request(
                            """{"kind":"thread.cancel","chatId":"$chatId","requestId":"cleanup","threadId":"$threadId"}"""
                        )
                        assertEquals("accepted", cleanup.acknowledgement["status"].asText())
                    }

                    val messages = client.get(BackendHttpRoutes.chatMessages(chatId)) {
                        trusted(userId)
                    }.jsonBody()["items"]
                    assertEquals(
                        listOf("stay active"),
                        messages.filter { it["role"].asText() == "user" }.map { it["content"].asText() },
                    )

                    llm.releaseCancellation()
                    eventually("cancelled thread") { threadStatus(chatId, threadId).takeIf { it == "cancelled" } }
                }
            } finally {
                llm.releaseCancellation()
            }
        }
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

    private suspend fun <T> BackendE2eScope.withPeerSockets(
        chatId: String,
        peerLlm: E2eLlmApi = E2eLlmApi(),
        block: suspend (List<SocketNode>) -> T,
    ): T {
        val primary = this
        return withPeerBackend(peerLlm) { peer ->
            val nodes = listOf(primary, peer).map { scope ->
                val client = scope.webSocketClient()
                SocketNode(
                    scope,
                    client,
                    client.webSocketSession("${BackendHttpRoutes.chatWebSocket(chatId)}?clientType=backend"),
                )
            }
            try {
                block(nodes)
            } finally {
                nodes.forEach { it.session.close() }
                nodes.forEach { it.client.close() }
            }
        }
    }

    private suspend fun race(nodes: List<SocketNode>, requests: List<String>): List<SocketReply> = coroutineScope {
        nodes.zip(requests).map { (node, request) -> async { node.request(request) } }.awaitAll()
    }

    private suspend fun SocketNode.request(raw: String): SocketReply {
        session.send(Frame.Text(raw))
        val acknowledgement = scope.readJson(session)
        val status = if (acknowledgement["status"].asText() == "accepted") scope.readJson(session) else null
        return SocketReply(acknowledgement, status)
    }

    private suspend fun BackendE2eScope.threadStatus(chatId: String, threadId: String): String =
        client.get("${BackendHttpRoutes.chatThread(chatId, threadId)}?clientType=backend")
            .jsonBody()["status"].asText()

}
