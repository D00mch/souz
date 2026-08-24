package ru.souz.backend.e2e

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.http.HttpStatusCode
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.http.BackendHttpRoutes

class BackendOwnershipE2eTest {
    @Test
    fun `provider keys are scoped to the trusted proxy user`() =
        backendE2eTest("e2e_provider_key_ownership") {
            val userA = "provider-owner-a"
            val userB = "provider-owner-b"

            val createdByA = client.put(BackendHttpRoutes.providerKey("qwen")) {
                trusted(userA)
                jsonBody("""{"apiKey":"qwen-secret-a"}""")
            }
            val listedByB = client.get(BackendHttpRoutes.PROVIDER_KEYS) {
                trusted(userB)
            }
            val deleteByB = client.delete(BackendHttpRoutes.providerKey("qwen")) {
                trusted(userB)
            }
            val listedByAAfterForeignDelete = client.get(BackendHttpRoutes.PROVIDER_KEYS) {
                trusted(userA)
            }
            val deleteByA = client.delete(BackendHttpRoutes.providerKey("qwen")) {
                trusted(userA)
            }
            val listedByAAfterOwnerDelete = client.get(BackendHttpRoutes.PROVIDER_KEYS) {
                trusted(userA)
            }

            assertEquals(HttpStatusCode.OK, createdByA.status)
            assertEquals("qwen", createdByA.jsonBody()["providerKey"]["provider"].asText())
            assertEquals(HttpStatusCode.OK, listedByB.status)
            assertEquals(0, listedByB.jsonBody()["items"].size())
            assertEquals(HttpStatusCode.NoContent, deleteByB.status)
            assertEquals(HttpStatusCode.OK, listedByAAfterForeignDelete.status)
            assertEquals(
                listOf("qwen"),
                listedByAAfterForeignDelete.jsonBody()["items"].map { it["provider"].asText() },
            )
            assertEquals(HttpStatusCode.NoContent, deleteByA.status)
            assertEquals(0, listedByAAfterOwnerDelete.jsonBody()["items"].size())
        }

    @Test
    fun `foreign users cannot replay events or cancel executions by chat or execution id`() =
        backendE2eTest(
            schemaPrefix = "e2e_execution_ownership",
            featureFlags = BackendFeatureFlags(wsEvents = true, streamingMessages = true),
            llm = E2eLlmApi().apply { hangUntilCancelled() },
        ) {
            val userA = UUID.randomUUID().toString()
            val userB = UUID.randomUUID().toString()
            val chatId = createPublicChat(userA)

            coroutineScope {
                val runningSend = async {
                    client.post(BackendHttpRoutes.chatMessages(chatId)) {
                        trusted(userA)
                        jsonBody("""{"content":"owned running turn","options":{"model":"${E2E_LOCAL_MODEL.alias}"}}""")
                    }
                }
                llm.awaitPrompt("owned running turn")
                val sent = runningSend.await()
                val executionId = sent.jsonBody()["execution"]["id"].asText()

                val foreignReplay = client.get(BackendHttpRoutes.chatEvents(chatId)) {
                    trusted(userB)
                }
                val foreignCancelActive = client.post(BackendHttpRoutes.cancelActive(chatId)) {
                    trusted(userB)
                }
                val foreignCancelById = client.post(BackendHttpRoutes.cancelExecution(chatId, executionId)) {
                    trusted(userB)
                }
                val foreignMessages = client.get(BackendHttpRoutes.chatMessages(chatId)) {
                    trusted(userB)
                }
                val ownerCancel = client.post(BackendHttpRoutes.cancelExecution(chatId, executionId)) {
                    trusted(userA)
                }

                assertEquals(HttpStatusCode.OK, sent.status)
                assertEquals(HttpStatusCode.NotFound, foreignReplay.status)
                assertEquals("chat_not_found", foreignReplay.jsonBody()["error"]["code"].asText())
                assertEquals(HttpStatusCode.NotFound, foreignCancelActive.status)
                assertEquals("chat_not_found", foreignCancelActive.jsonBody()["error"]["code"].asText())
                assertEquals(HttpStatusCode.NotFound, foreignCancelById.status)
                assertEquals("chat_not_found", foreignCancelById.jsonBody()["error"]["code"].asText())
                assertEquals(HttpStatusCode.NotFound, foreignMessages.status)
                assertEquals("chat_not_found", foreignMessages.jsonBody()["error"]["code"].asText())
                assertEquals(HttpStatusCode.OK, ownerCancel.status)
                assertTrue(ownerCancel.jsonBody()["execution"]["cancelRequested"].asBoolean())
            }

            val ownerEvents = eventually("owner cancelled event") {
                client.get(BackendHttpRoutes.chatEvents(chatId)) {
                    trusted(userA)
                }.jsonBody()["items"].takeIf { events ->
                    events.any { it["type"].asText() == "execution.cancelled" }
                }
            }
            assertEquals("execution.cancelled", ownerEvents.last()["type"].asText())
        }

    private suspend fun BackendE2eScope.createPublicChat(userId: String): String {
        val created = client.post(BackendHttpRoutes.CHATS) {
            jsonBody("""{"userId":"$userId","requestId":"create-1","clientType":"backend"}""")
        }
        assertEquals(HttpStatusCode.Created, created.status)
        return created.jsonBody()["chat"]["id"].asText()
    }
}
