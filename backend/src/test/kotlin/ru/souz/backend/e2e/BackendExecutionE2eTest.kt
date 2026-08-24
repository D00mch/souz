package ru.souz.backend.e2e

import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.http.BackendHttpRoutes

class BackendExecutionE2eTest {
    @Test
    fun `real kernel completes an HTTP turn and exposes durable events without live deltas`() =
        backendE2eTest(
            schemaPrefix = "e2e_execution_success",
            featureFlags = BackendFeatureFlags(wsEvents = true, streamingMessages = true, toolEvents = true),
        ) {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            val settings = client.patch(BackendHttpRoutes.SETTINGS) {
                trusted(userId)
                jsonBody("""{"defaultModel":"${E2E_LOCAL_MODEL.alias}","streamingMessages":true}""")
            }
            assertEquals(HttpStatusCode.OK, settings.status)

            val sent = client.post(BackendHttpRoutes.chatMessages(chatId)) {
                trusted(userId)
                jsonBody("""{"content":"stream me"}""")
            }
            val executionId = sent.jsonBody()["execution"]["id"].asText()
            assertEquals(HttpStatusCode.OK, sent.status)
            assertTrue(sent.jsonBody()["assistantMessage"].isNull)

            val events = eventually("finished durable execution events") {
                client.get(BackendHttpRoutes.chatEvents(chatId)) {
                    trusted(userId)
                }.jsonBody()["items"].takeIf { items ->
                    items.any { it["type"].asText() == "execution.finished" }
                }
            }
            val messages = client.get(BackendHttpRoutes.chatMessages(chatId)) {
                trusted(userId)
            }.jsonBody()["items"]

            assertEquals(listOf("user", "assistant"), messages.map { it["role"].asText() })
            assertEquals("assistant reply to stream me", messages.last()["content"].asText())
            assertTrue(events.all { it["executionId"].asText() == executionId })
            assertFalse(events.any { it["type"].asText() == "message.delta" })
            assertEquals(
                listOf(
                    "message.created",
                    "execution.started",
                    "message.created",
                    "message.completed",
                    "execution.finished",
                ),
                events.map { it["type"].asText() },
            )
        }

    @Test
    fun `failed execution records terminal event and no partial assistant message`() =
        backendE2eTest(
            "e2e_execution_failure",
            featureFlags = BackendFeatureFlags(wsEvents = true, streamingMessages = true),
            llm = E2eLlmApi().apply { streamThenFail(listOf("partial ", "assistant"), "simulated failure") },
        ) {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            client.patch(BackendHttpRoutes.SETTINGS) {
                trusted(userId)
                jsonBody("""{"defaultModel":"${E2E_LOCAL_MODEL.alias}","streamingMessages":true}""")
            }
            val sent = client.post(BackendHttpRoutes.chatMessages(chatId)) {
                trusted(userId)
                jsonBody("""{"content":"fail please"}""")
            }

            assertEquals(HttpStatusCode.OK, sent.status)
            val events = eventually("failed durable event") {
                client.get(BackendHttpRoutes.chatEvents(chatId)) {
                    trusted(userId)
                }.jsonBody()["items"].takeIf { items ->
                    items.any { it["type"].asText() == "execution.failed" }
                }
            }
            val messages = client.get(BackendHttpRoutes.chatMessages(chatId)) {
                trusted(userId)
            }.jsonBody()["items"]

            assertEquals(listOf("message.created", "execution.started", "execution.failed"), events.map { it["type"].asText() })
            assertEquals(listOf("user"), messages.map { it["role"].asText() })
            assertEquals(listOf("partial ", "assistant"), llm.streamedChunks)
            assertEquals("agent_execution_failed", events.last()["payload"]["errorCode"].asText())
        }

    @Test
    fun `concurrent HTTP turn conflicts and cancellation reaches a terminal event`() =
        backendE2eTest(
            "e2e_execution_cancel",
            featureFlags = BackendFeatureFlags(wsEvents = true, streamingMessages = true),
            llm = E2eLlmApi().apply { streamThenHang(listOf("partial ", "assistant")) },
        ) {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            client.patch(BackendHttpRoutes.SETTINGS) {
                trusted(userId)
                jsonBody("""{"defaultModel":"${E2E_LOCAL_MODEL.alias}","streamingMessages":true}""")
            }
            coroutineScope {
                val runningSend = async {
                    client.post(BackendHttpRoutes.chatMessages(chatId)) {
                        trusted(userId)
                        jsonBody("""{"content":"cancel me","options":{"model":"${E2E_LOCAL_MODEL.alias}"}}""")
                    }
                }
                llm.awaitPrompt("cancel me")
                eventually("streamed partial response") {
                    llm.streamedChunks.takeIf { it.size == 2 }
                }
                val conflicting = client.post(BackendHttpRoutes.chatMessages(chatId)) {
                    trusted(userId)
                    jsonBody("""{"content":"second message"}""")
                }
                val cancel = client.post(BackendHttpRoutes.cancelActive(chatId)) {
                    trusted(userId)
                }
                val sent = runningSend.await()

                assertEquals(HttpStatusCode.OK, sent.status)
                assertEquals(HttpStatusCode.Conflict, conflicting.status)
                assertEquals("chat_already_has_active_execution", conflicting.jsonBody()["error"]["code"].asText())
                assertEquals(HttpStatusCode.OK, cancel.status)
            }
            val events = eventually("cancelled durable event") {
                client.get(BackendHttpRoutes.chatEvents(chatId)) {
                    trusted(userId)
                }.jsonBody()["items"].takeIf { items ->
                    items.any { it["type"].asText() == "execution.cancelled" }
                }
            }
            assertEquals("execution.cancelled", events.last()["type"].asText())
            val messages = client.get(BackendHttpRoutes.chatMessages(chatId)) {
                trusted(userId)
            }.jsonBody()["items"]
            assertEquals(listOf("user"), messages.map { it["role"].asText() })
        }

    @Test
    fun `tool audit events redact secrets while production delivery keeps the original payload`() {
        val secret = "sk-audit-secret-123"
        val deliveredText = "Authorization: Bearer $secret"
        val prompt = "deliver an audit payload"
        val llm = E2eLlmApi()
        backendE2eTest(
            schemaPrefix = "e2e_execution_audit",
            featureFlags = BackendFeatureFlags(wsEvents = true, toolEvents = true),
            llm = llm,
        ) {
            val userId = UUID.randomUUID().toString()
            val sourceChatId = createPublicChat(userId, "create-source")
            val targetChatId = createPublicChat(userId, "create-target")
            llm.requestSkillForPrompt(
                prompt = prompt,
                skillId = "SendMessageToChannel",
                arguments = mapOf(
                    "channelType" to "public_client",
                    "channelId" to targetChatId,
                    "text" to deliveredText,
                ),
            )

            val sent = client.post(BackendHttpRoutes.chatMessages(sourceChatId)) {
                trusted(userId)
                jsonBody("""{"content":"$prompt","options":{"model":"${E2E_LOCAL_MODEL.alias}"}}""")
            }
            assertEquals(HttpStatusCode.OK, sent.status)
            val auditResponse = eventually("redacted tool audit events") {
                client.get(BackendHttpRoutes.chatEvents(sourceChatId)) {
                    trusted(userId)
                }.takeIf { response ->
                    val items = response.jsonBody()["items"]
                    items.any { event ->
                        event["type"].asText() == "tool.call.started" &&
                            event["payload"]["name"].asText() == "RunSkillCommand"
                    } && items.any { event -> event["type"].asText() == "execution.finished" }
                }
            }
            val auditBody = auditResponse.bodyAsText()
            assertFalse(auditBody.contains(secret))
            assertTrue(auditBody.contains("[REDACTED]"))

            val delivered = client.get(BackendHttpRoutes.chatMessages(targetChatId)) {
                trusted(userId)
            }.jsonBody()["items"].single()
            assertEquals(deliveredText, delivered["content"].asText())
            assertEquals("true", delivered["metadata"]["crossChannel"].asText())
        }
    }

    private suspend fun BackendE2eScope.createPublicChat(
        userId: String,
        requestId: String = "create-1",
    ): String {
        val created = client.post(BackendHttpRoutes.CHATS) {
            jsonBody("""{"userId":"$userId","requestId":"$requestId","clientType":"backend"}""")
        }
        assertEquals(HttpStatusCode.Created, created.status)
        return created.jsonBody()["chat"]["id"].asText()
    }
}
