package ru.souz.backend.e2e

import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.Frame
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
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
                assertEquals(assistantAck.deepCopy<ObjectNode>().put("duplicate", true), duplicate)

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
                    historyFrame(chatId, "missing-role", "user", "invalid")
                        .replace("\"role\":\"user\",", ""),
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
        val chatId = createPublicChat(userId, "create-history")
        return withPublicSocket(chatId) { session ->
            try {
                block(userId, chatId, session)
            } finally {
                cleanup()
            }
        }
    }

    private fun toolHistoryFrame(chatId: String, requestId: String): String =
        """{"kind":"history.append","chatId":"$chatId","requestId":"$requestId","payload":{"role":"assistant","content":{"type":"tool_exchange","name":"device.volume.adjust","arguments":{"deltaPercent":-10},"output":{"volumePercent":30}}}}"""

}
