package ru.souz.backend.e2e

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import ru.souz.backend.chat.model.CLIENT_HISTORY_MESSAGE_METADATA_KEY
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.llms.LLMMessageRole

class BackendPublicHistoryContractE2eTest {
    @Test
    fun `history contract is strict durable and thread independent`() =
        backendE2eTest("e2e_ws_history_contract") {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            val wsClient = webSocketClient()
            val session = wsClient.webSocketSession(
                "${BackendHttpRoutes.chatWebSocket(chatId)}?clientType=backend"
            )

            try {
                session.send(Frame.Text(historyFrame(chatId, userId, "history-user", "user", "client solved it")))
                val userAck = readJson(session)
                assertEquals("accepted", userAck["status"].asText())
                assertFalse(userAck["duplicate"].asBoolean())
                assertFalse(userAck.has("mode"))
                assertFalse(userAck.has("submission"))
                assertFalse(userAck.has("thread"))

                val assistantFrame = historyFrame(
                    chatId,
                    userId,
                    "history-assistant",
                    "assistant",
                    "the client task is complete",
                )
                session.send(Frame.Text(assistantFrame))
                val assistantAck = readJson(session)
                assertEquals("accepted", assistantAck["status"].asText())

                session.send(Frame.Text(assistantFrame))
                val duplicate = readJson(session)
                assertEquals("accepted", duplicate["status"].asText())
                assertTrue(duplicate["duplicate"].asBoolean())
                assertEquals(assistantAck["receivedAt"], duplicate["receivedAt"])

                session.send(
                    Frame.Text(
                        historyFrame(
                            chatId,
                            userId,
                            "history-assistant",
                            "user",
                            "the client task is complete",
                        )
                    )
                )
                val conflict = readJson(session)
                assertEquals("rejected", conflict["status"].asText())
                assertEquals("idempotency_conflict", conflict["error"]["code"].asText())

                listOf(
                    messageFrame(
                        chatId,
                        userId,
                        "history-user",
                        text = "client solved it",
                    ),
                    historyFrame(chatId, userId, "history-user", "user", "changed content"),
                ).forEach { changedFrame ->
                    session.send(Frame.Text(changedFrame))
                    assertEquals("idempotency_conflict", readJson(session)["error"]["code"].asText())
                }

                val invalidFrames = listOf(
                    historyFrame(chatId, userId, "history-thread", "user", "invalid")
                        .replace("\"payload\":", "\"threadId\":null,\n          \"payload\":"),
                    messageFrame(chatId, userId, "execute-role")
                        .replace("\"device\":", "\"role\":\"assistant\",\n            \"device\":"),
                    messageFrame(chatId, userId, "execute-mode")
                        .replace("\"chatId\":", "\"mode\":\"execute\",\n          \"chatId\":"),
                    historyFrame(chatId, userId, "missing-role", "user", "invalid")
                        .replace("\"role\":\"user\",", ""),
                    historyFrame(chatId, userId, "null-role", "user", "invalid")
                        .replace("\"role\":\"user\"", "\"role\":null"),
                    historyFrame(chatId, userId, "unknown-role", "tool", "invalid"),
                )
                invalidFrames.forEach { raw ->
                    session.send(Frame.Text(raw))
                    val rejected = readJson(session)
                    assertEquals("rejected", rejected["status"].asText())
                    assertEquals("invalid_request", rejected["error"]["code"].asText())
                }

                assertTrue(llm.requests.isEmpty())
                assertEquals(0, sql { connection ->
                    connection.prepareStatement("select count(*) from agent_executions where chat_id = ?").use { statement ->
                        statement.setObject(1, UUID.fromString(chatId))
                        statement.executeQuery().use { rows ->
                            rows.next()
                            rows.getInt(1)
                        }
                    }
                })
                assertEquals(0, sql { connection ->
                    connection.prepareStatement("select count(*) from agent_events where chat_id = ?").use { statement ->
                        statement.setObject(1, UUID.fromString(chatId))
                        statement.executeQuery().use { rows ->
                            rows.next()
                            rows.getInt(1)
                        }
                    }
                })
                val stored = sql { connection ->
                    connection.prepareStatement(
                        "select role, content, metadata from messages where chat_id = ? order by seq asc"
                    ).use { statement ->
                        statement.setObject(1, UUID.fromString(chatId))
                        statement.executeQuery().use { rows ->
                            buildList {
                                while (rows.next()) {
                                    add(Triple(rows.getString("role"), rows.getString("content"), rows.getString("metadata")))
                                }
                            }
                        }
                    }
                }
                assertEquals(listOf("user", "assistant"), stored.map { it.first })
                assertEquals(
                    listOf("client solved it", "the client task is complete"),
                    stored.map { it.second },
                )
                stored.forEach { (_, _, metadata) ->
                    assertEquals("true", json.readTree(metadata)[CLIENT_HISTORY_MESSAGE_METADATA_KEY].asText())
                    assertNull(json.readTree(metadata)["inputSeq"])
                }
            } finally {
                session.close()
                wsClient.close()
            }
        }

    @Test
    fun `history during an active request waits for implicit execute and keeps the active thread`() {
        val llm = E2eLlmApi().apply { pausePromptUntilReleased("first active") }
        backendE2eTest("e2e_ws_history_active_thread", llm = llm) {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            val wsClient = webSocketClient()
            val session = wsClient.webSocketSession(
                "${BackendHttpRoutes.chatWebSocket(chatId)}?clientType=backend"
            )

            try {
                session.send(
                    Frame.Text(
                        messageFrame(
                            chatId,
                            userId,
                            "active-first",
                            text = "first active",
                        )
                    )
                )
                val firstAck = readJson(session)
                assertFalse(firstAck.has("mode"))
                val threadId = firstAck["thread"]["id"].asText()
                readJson(session) // thread.status
                llm.awaitPrompt("first active")

                session.send(
                    Frame.Text(
                        historyFrame(chatId, userId, "active-history-user", "user", "client side request")
                    )
                )
                assertEquals("accepted", readJson(session)["status"].asText())
                session.send(
                    Frame.Text(
                        historyFrame(
                            chatId,
                            userId,
                            "active-history-assistant",
                            "assistant",
                            "client side response",
                        )
                    )
                )
                assertEquals("accepted", readJson(session)["status"].asText())
                delay(100)
                assertEquals(1, llm.requests.size)

                session.send(
                    Frame.Text(
                        messageFrame(
                            chatId,
                            userId,
                            "active-second",
                            text = "second active",
                        )
                    )
                )
                val secondAck = readJson(session)
                assertEquals(threadId, secondAck["thread"]["id"].asText())
                assertFalse(secondAck["thread"]["created"].asBoolean())
                assertEquals(2L, secondAck["thread"]["revision"].asLong())
                readJson(session) // thread.status
                llm.awaitPrompt("second active")
                readJson(session) // thread.completed

                val currentRequest = llm.requests.last()
                val relevantMessages = currentRequest.messages.filter {
                    it.content in setOf(
                        "first active",
                        "client side request",
                        "client side response",
                        "second active",
                    )
                }
                assertEquals(
                    listOf(
                        LLMMessageRole.user,
                        LLMMessageRole.user,
                        LLMMessageRole.assistant,
                        LLMMessageRole.user,
                    ),
                    relevantMessages.map { it.role },
                )
                assertEquals(
                    listOf("first active", "client side request", "client side response", "second active"),
                    relevantMessages.map { it.content },
                )
                assertEquals(2, llm.requests.size)
            } finally {
                llm.releasePrompt("first active")
                session.close()
                wsClient.close()
            }
        }
    }

    @Test
    fun `history accepted by a non owner is recovered by the next owner execute`() {
        val ownerLlm = E2eLlmApi().apply { pausePromptUntilReleased("owner active") }
        val peerLlm = E2eLlmApi()
        backendE2eTest("e2e_ws_history_cross_instance", llm = ownerLlm) {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            val ownerClient = webSocketClient()
            val ownerSession = ownerClient.webSocketSession(
                "${BackendHttpRoutes.chatWebSocket(chatId)}?clientType=backend"
            )

            try {
                ownerSession.send(
                    Frame.Text(
                        messageFrame(
                            chatId,
                            userId,
                            "owner-first",
                            text = "owner active",
                        )
                    )
                )
                val firstAck = readJson(ownerSession)
                val threadId = firstAck["thread"]["id"].asText()
                readJson(ownerSession) // thread.status
                ownerLlm.awaitPrompt("owner active")

                withPeerBackend(peerLlm) { peer ->
                    val peerClient = peer.webSocketClient()
                    val peerSession = peerClient.webSocketSession(
                        "${BackendHttpRoutes.chatWebSocket(chatId)}?clientType=backend"
                    )
                    try {
                        peerSession.send(
                            Frame.Text(
                                historyFrame(
                                    chatId,
                                    userId,
                                    "peer-history",
                                    "assistant",
                                    "completed by peer",
                                )
                            )
                        )
                        val historyAck = peer.readJson(peerSession)
                        assertEquals("accepted", historyAck["status"].asText())
                        assertFalse(historyAck.has("thread"))
                        delay(100)
                        assertEquals(1, ownerLlm.requests.size)
                        assertTrue(peerLlm.requests.isEmpty())

                        ownerSession.send(
                            Frame.Text(
                                messageFrame(
                                    chatId,
                                    userId,
                                    "owner-second",
                                    text = "owner follow up",
                                )
                            )
                        )
                        val secondAck = readJson(ownerSession)
                        assertEquals(threadId, secondAck["thread"]["id"].asText())
                        assertFalse(secondAck["thread"]["created"].asBoolean())
                        readJson(ownerSession) // thread.status
                        ownerLlm.awaitPrompt("owner follow up")
                        readJson(ownerSession) // thread.completed

                        val replacement = ownerLlm.requests.last().messages
                        assertEquals(
                            listOf(LLMMessageRole.assistant, LLMMessageRole.user),
                            replacement
                                .filter { it.content in setOf("completed by peer", "owner follow up") }
                                .map { it.role },
                        )
                        assertEquals(1, replacement.count { it.content == "completed by peer" })
                        assertTrue(peerLlm.requests.isEmpty())
                    } finally {
                        peerSession.close()
                        peerClient.close()
                    }
                }
            } finally {
                ownerLlm.releasePrompt("owner active")
                ownerSession.close()
                ownerClient.close()
            }
        }
    }

    @Test
    fun `history received during a client tool stays before its complete exchange`() =
        backendE2eTest(
            schemaPrefix = "e2e_ws_history_tool_order",
            llm = E2eLlmApi().apply {
                requestSkill("user.ask", mapOf("question" to "Which genre?"))
            },
        ) {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            val wsClient = webSocketClient()
            val session = wsClient.webSocketSession(
                "${BackendHttpRoutes.chatWebSocket(chatId)}?clientType=backend"
            )

            try {
                session.send(
                    Frame.Text(
                        messageFrame(
                            chatId,
                            userId,
                            "execute-tool",
                            text = "ask the client",
                        )
                    )
                )
                val executeAck = readJson(session)
                readJson(session) // thread.status
                val toolStarted = readJson(session)
                val threadId = executeAck["thread"]["id"].asText()
                val toolCallId = toolStarted["payload"]["toolCallId"].asText()
                assertEquals("tool.call.started", toolStarted["type"].asText())
                assertEquals(2, llm.requests.size)

                session.send(
                    Frame.Text(
                        historyFrame(
                            chatId,
                            userId,
                            "history-during-tool",
                            "assistant",
                            "client context during tool",
                        )
                    )
                )
                assertEquals("accepted", readJson(session)["status"].asText())
                delay(100)
                assertEquals(2, llm.requests.size)

                session.send(
                    Frame.Text(
                        """{"kind":"tool.result","chatId":"$chatId","threadId":"$threadId","toolCallId":"$toolCallId","status":"succeeded","result":{"answer":"Horror"}}"""
                    )
                )
                assertEquals("accepted", readJson(session)["status"].asText())
                readJson(session) // thread.completed

                val finalRequest = llm.requests.last().messages
                val historyIndex = finalRequest.indexOfFirst { it.content == "client context during tool" }
                val toolCallIndex = finalRequest.indexOfFirst {
                    it.role == LLMMessageRole.assistant && it.functionCall?.name == "RunSkillCommand"
                }
                val toolResultIndex = finalRequest.indexOfFirst {
                    it.role == LLMMessageRole.function && it.name == "RunSkillCommand"
                }
                assertTrue(historyIndex >= 0)
                assertTrue(historyIndex < toolCallIndex)
                assertTrue(toolCallIndex < toolResultIndex)
                assertEquals(1, finalRequest.count { it.content == "client context during tool" })
            } finally {
                session.close()
                wsClient.close()
            }
        }

    @Test
    fun `history during a final response remains after that response until the next execute`() {
        val llm = E2eLlmApi().apply { pausePromptUntilReleased("final active") }
        backendE2eTest("e2e_ws_history_final_gap", llm = llm) {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            val wsClient = webSocketClient()
            val session = wsClient.webSocketSession(
                "${BackendHttpRoutes.chatWebSocket(chatId)}?clientType=backend"
            )

            try {
                session.send(
                    Frame.Text(
                        messageFrame(
                            chatId,
                            userId,
                            "execute-final",
                            text = "final active",
                        )
                    )
                )
                readJson(session) // acknowledgement
                readJson(session) // thread.status
                llm.awaitPrompt("final active")

                session.send(
                    Frame.Text(
                        historyFrame(
                            chatId,
                            userId,
                            "history-before-final",
                            "assistant",
                            "late client history",
                        )
                    )
                )
                assertEquals("accepted", readJson(session)["status"].asText())
                delay(100)
                assertEquals(1, llm.requests.size)

                llm.releasePrompt("final active")
                readJson(session) // thread.completed

                session.send(
                    Frame.Text(
                        messageFrame(
                            chatId,
                            userId,
                            "execute-after-final",
                            text = "after final",
                        )
                    )
                )
                readJson(session) // acknowledgement
                readJson(session) // thread.status
                readJson(session) // thread.completed

                val nextRequest = llm.requests.last().messages
                val savedResponseIndex = nextRequest.indexOfFirst {
                    it.role == LLMMessageRole.assistant && it.content == "assistant reply to final active"
                }
                val historyIndex = nextRequest.indexOfFirst { it.content == "late client history" }
                val executeIndex = nextRequest.indexOfFirst { it.content == "after final" }
                assertTrue(savedResponseIndex >= 0)
                assertTrue(savedResponseIndex < historyIndex)
                assertTrue(historyIndex < executeIndex)
                assertEquals(1, nextRequest.count { it.content == "late client history" })
            } finally {
                llm.releasePrompt("final active")
                session.close()
                wsClient.close()
            }
        }
    }

    @Test
    fun `implicit execute creates a thread with preceding role preserving history`() =
        backendE2eTest("e2e_ws_history_new_thread") {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            val wsClient = webSocketClient()
            val session = wsClient.webSocketSession(
                "${BackendHttpRoutes.chatWebSocket(chatId)}?clientType=backend"
            )

            try {
                session.send(Frame.Text(historyFrame(chatId, userId, "history-before-1", "user", "solved request")))
                assertEquals("accepted", readJson(session)["status"].asText())
                session.send(
                    Frame.Text(
                        historyFrame(chatId, userId, "history-before-2", "assistant", "request solved")
                    )
                )
                assertEquals("accepted", readJson(session)["status"].asText())

                session.send(
                    Frame.Text(
                        messageFrame(
                            chatId,
                            userId,
                            "execute-after-history",
                            text = "do something new",
                        )
                    )
                )
                val ack = readJson(session)
                assertEquals("accepted", ack["status"].asText())
                assertTrue(ack["thread"]["created"].asBoolean())
                assertEquals(1L, ack["submission"]["inputSeq"].asLong())
                readJson(session) // thread.status
                readJson(session) // thread.completed

                val relevantMessages = llm.requests.single().messages.filter {
                    it.content in setOf("solved request", "request solved", "do something new")
                }
                assertEquals(
                    listOf(LLMMessageRole.user, LLMMessageRole.assistant, LLMMessageRole.user),
                    relevantMessages.map { it.role },
                )
                assertEquals(
                    listOf("solved request", "request solved", "do something new"),
                    relevantMessages.map { it.content },
                )

                session.send(
                    Frame.Text(
                        messageFrame(
                            chatId,
                            userId,
                            "execute-again",
                            text = "one more thing",
                        )
                    )
                )
                assertEquals("accepted", readJson(session)["status"].asText())
                readJson(session) // thread.status
                readJson(session) // thread.completed
                val nextRequest = llm.requests[1]
                assertEquals(1, nextRequest.messages.count { it.content == "solved request" })
                assertEquals(1, nextRequest.messages.count { it.content == "request solved" })
            } finally {
                session.close()
                wsClient.close()
            }
        }

    private suspend fun BackendE2eScope.createPublicChat(userId: String): String {
        val created = client.post(BackendHttpRoutes.CHATS) {
            jsonBody("""{"userId":"$userId","requestId":"create-history","clientType":"backend"}""")
        }
        assertEquals(HttpStatusCode.Created, created.status)
        return created.jsonBody()["chat"]["id"].asText()
    }

    private suspend fun BackendE2eScope.readJson(session: DefaultClientWebSocketSession): JsonNode =
        json.readTree((session.incoming.receive() as Frame.Text).readText())

    private fun historyFrame(
        chatId: String,
        userId: String,
        requestId: String,
        role: String,
        text: String,
    ): String = clientMessageFrame(
        kind = "history.append",
        chatId = chatId,
        userId = userId,
        requestId = requestId,
        role = role,
        text = text,
    )

    private fun messageFrame(
        chatId: String,
        userId: String,
        requestId: String,
        text: String = "execute this",
    ): String = clientMessageFrame("message.submit", chatId, userId, requestId, text = text)

    private fun clientMessageFrame(
        kind: String,
        chatId: String,
        userId: String,
        requestId: String,
        role: String? = null,
        text: String,
    ): String =
        """
        {
          "kind": "$kind",
          "chatId": "$chatId",
          "requestId": "$requestId",
          "payload": {
            ${role?.let { "\"role\":\"$it\"," }.orEmpty()}
            "device": {
              "userId": "$userId",
              "deviceId": "history-device",
              "deviceType": "tv_box",
              "capabilities": ["speech", "screen", "device_tools"]
            },
            "content": {"type":"text","source":"text","text":"$text"},
            "meta": {"model":"${E2E_LOCAL_MODEL.alias}","locale":"en-US","timeZone":"UTC"}
          }
        }
        """.trimIndent()
}
