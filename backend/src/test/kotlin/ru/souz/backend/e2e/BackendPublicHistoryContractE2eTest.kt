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
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import ru.souz.backend.chat.model.CLIENT_HISTORY_MESSAGE_METADATA_KEY
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.llms.LLMMessageRole

class BackendPublicHistoryContractE2eTest {
    @Test
    fun `history contract is strict durable and thread independent`() =
        backendE2eTest("e2e_ws_history_contract") {
            withPublicChatSocket { userId, chatId, session ->
                session.send(Frame.Text(historyFrame(chatId, "history-user", "user", "client solved it")))
                val userAck = readJson(session)
                assertEquals("accepted", userAck["status"].asText())
                assertFalse(userAck["duplicate"].asBoolean())
                assertFalse(userAck.has("mode"))
                assertFalse(userAck.has("submission"))
                assertFalse(userAck.has("thread"))

                val assistantFrame = historyFrame(
                    chatId,
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
                    historyFrame(chatId, "history-user", "user", "changed content"),
                ).forEach { changedFrame ->
                    session.send(Frame.Text(changedFrame))
                    assertEquals("idempotency_conflict", readJson(session)["error"]["code"].asText())
                }

                val invalidFrames = listOf(
                    historyFrame(chatId, "history-thread", "user", "invalid")
                        .replace("\"payload\":", "\"threadId\":null,\n          \"payload\":"),
                    messageFrame(chatId, userId, "execute-role")
                        .replace("\"device\":", "\"role\":\"assistant\",\n            \"device\":"),
                    messageFrame(chatId, userId, "execute-mode")
                        .replace("\"chatId\":", "\"mode\":\"execute\",\n          \"chatId\":"),
                    historyFrame(chatId, "history-device", "user", "invalid")
                        .replace(
                            "\"content\":",
                            "\"device\":{\"userId\":\"$userId\",\"deviceId\":\"history-device\",\"deviceType\":\"tv_box\",\"capabilities\":[]},\n            \"content\":",
                        ),
                    historyFrame(chatId, "history-meta", "user", "invalid")
                        .replace("\"content\":", "\"meta\":{},\n            \"content\":"),
                    historyFrame(chatId, "missing-role", "user", "invalid")
                        .replace("\"role\":\"user\",", ""),
                    historyFrame(chatId, "null-role", "user", "invalid")
                        .replace("\"role\":\"user\"", "\"role\":null"),
                    historyFrame(chatId, "unknown-role", "tool", "invalid"),
                    toolHistoryFrame(chatId, "tool-user-role")
                        .replace("\"role\":\"assistant\"", "\"role\":\"user\""),
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
                    val metadataJson = json.readTree(metadata)
                    assertEquals(1, metadataJson.size())
                    assertEquals("true", metadataJson[CLIENT_HISTORY_MESSAGE_METADATA_KEY].asText())
                }

                session.send(Frame.Text(toolHistoryFrame(chatId, "history-tool")))
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
                val executeAck = readJson(session)
                assertEquals("accepted", executeAck["status"].asText())
                assertTrue(executeAck["thread"]["created"].asBoolean())
                readJson(session) // thread.status
                readJson(session) // thread.completed

                val requestMessages = llm.requests.single().messages
                val relevantMessages = requestMessages.filter {
                    it.content in setOf(
                        "client solved it",
                        "the client task is complete",
                        "do something new",
                    )
                }
                assertEquals(
                    listOf(LLMMessageRole.user, LLMMessageRole.assistant, LLMMessageRole.user),
                    relevantMessages.map { it.role },
                )
                val toolCall = requestMessages.single { it.functionCall?.name == "RunSkillCommand" }
                val toolResult = requestMessages.single {
                    it.name == "RunSkillCommand" && it.functionsStateId == toolCall.functionsStateId
                }
                val runSkillArguments = json.readTree(checkNotNull(toolCall.functionCall).arguments)
                assertEquals("device.volume.adjust", runSkillArguments["skillId"].asText())
                assertEquals(-10, runSkillArguments["arguments"]["deltaPercent"].asInt())
                assertEquals(30, json.readTree(toolResult.content)["volumePercent"].asInt())
            }
        }

    @Test
    fun `history during an active request waits for implicit execute and keeps the active thread`() {
        val llm = E2eLlmApi().apply { pausePromptUntilReleased("first active") }
        backendE2eTest("e2e_ws_history_active_thread", llm = llm) {
            withPublicChatSocket(
                cleanup = { llm.releasePrompt("first active") },
            ) { userId, chatId, session ->
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
                        historyFrame(chatId, "active-history-user", "user", "client side request")
                    )
                )
                assertEquals("accepted", readJson(session)["status"].asText())
                session.send(
                    Frame.Text(
                        historyFrame(
                            chatId,
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
            }
        }
    }

    @Test
    fun `history accepted by a non owner is recovered by the next owner execute`() {
        val ownerLlm = E2eLlmApi().apply { pausePromptUntilReleased("owner active") }
        val peerLlm = E2eLlmApi()
        backendE2eTest("e2e_ws_history_cross_instance", llm = ownerLlm) {
            withPublicChatSocket(
                cleanup = { ownerLlm.releasePrompt("owner active") },
            ) { userId, chatId, ownerSession ->
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
                    peer.withPublicSocket(chatId) { peerSession ->
                        peerSession.send(
                            Frame.Text(
                                historyFrame(
                                    chatId,
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
                    }
                }
            }
        }
    }

    @Test
    fun `history during a final response remains after that response until the next execute`() {
        val llm = E2eLlmApi().apply { pausePromptUntilReleased("final active") }
        backendE2eTest("e2e_ws_history_final_gap", llm = llm) {
            withPublicChatSocket(
                cleanup = { llm.releasePrompt("final active") },
            ) { userId, chatId, session ->
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
            }
        }
    }

    private suspend fun <T> BackendE2eScope.withPublicChatSocket(
        cleanup: suspend () -> Unit = {},
        block: suspend (userId: String, chatId: String, session: DefaultClientWebSocketSession) -> T,
    ): T {
        val userId = UUID.randomUUID().toString()
        val chatId = createPublicChat(userId)
        return withPublicSocket(chatId) { session ->
            try {
                block(userId, chatId, session)
            } finally {
                cleanup()
            }
        }
    }

    private suspend fun <T> BackendE2eScope.withPublicSocket(
        chatId: String,
        block: suspend (DefaultClientWebSocketSession) -> T,
    ): T {
        val client = webSocketClient()
        val session = client.webSocketSession(
            "${BackendHttpRoutes.chatWebSocket(chatId)}?clientType=backend"
        )
        return try {
            block(session)
        } finally {
            session.close()
            client.close()
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
        requestId: String,
        role: String,
        text: String,
    ): String =
        """
        {
          "kind": "history.append",
          "chatId": "$chatId",
          "requestId": "$requestId",
          "payload": {
            "role":"$role",
            "content": {"type":"text","source":"text","text":"$text"}
          }
        }
        """.trimIndent()

    private fun toolHistoryFrame(chatId: String, requestId: String): String =
        """{"kind":"history.append","chatId":"$chatId","requestId":"$requestId","payload":{"role":"assistant","content":{"type":"tool_exchange","name":"device.volume.adjust","arguments":{"deltaPercent":-10},"output":{"volumePercent":30}}}}"""

    private fun messageFrame(
        chatId: String,
        userId: String,
        requestId: String,
        text: String = "execute this",
    ): String =
        """
        {
          "kind": "message.submit",
          "chatId": "$chatId",
          "requestId": "$requestId",
          "payload": {
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
