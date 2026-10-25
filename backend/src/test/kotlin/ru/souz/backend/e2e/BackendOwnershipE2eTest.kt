package ru.souz.backend.e2e

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.llms.http.ProviderHttpClients
import ru.souz.llms.http.providerHttpClientDefaults
import ru.souz.llms.restJsonMapper

class BackendOwnershipE2eTest {
    @Test
    fun `provider keys and execution credentials are scoped to the trusted proxy user`() {
        val capturedCredentials = CopyOnWriteArrayList<Pair<String, String?>>()
        val http = HttpClient(MockEngine { request ->
            val body = restJsonMapper.readTree(request.body.toByteArray())
            val prompt = body["messages"].map { it["content"].asText() }
                .first { it == "credentials-a" || it == "credentials-b" }
            capturedCredentials += prompt to request.headers[HttpHeaders.Authorization]
            respond(
                """{"choices":[{"index":0,"message":{"role":"assistant","content":"credentials checked"},"finish_reason":"stop"}],"created":1}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }) { providerHttpClientDefaults() }
        backendE2eTest(
            "e2e_provider_key_ownership",
            mockProvider = false,
            providerClients = ProviderHttpClients(standard = http, openAi = http),
        ) {
            val userA = UUID.randomUUID().toString()
            val userB = UUID.randomUUID().toString()

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
            assertEquals(HttpStatusCode.OK, client.put(BackendHttpRoutes.providerKey("qwen")) {
                trusted(userB)
                jsonBody("""{"apiKey":"qwen-secret-b"}""")
            }.status)
            coroutineScope {
                listOf(userA to "credentials-a", userB to "credentials-b").map { (userId, prompt) ->
                    async {
                        val chatId = createPublicChat(userId)
                        assertEquals(HttpStatusCode.OK, client.post(BackendHttpRoutes.chatMessages(chatId)) {
                            trusted(userId)
                            jsonBody("""{"content":"$prompt"}""")
                        }.status)
                        eventually("owner-specific provider reply") {
                            client.get(BackendHttpRoutes.chatMessages(chatId)) { trusted(userId) }.jsonBody()["items"]
                                .takeIf { it.last()["content"].asText() == "credentials checked" }
                        }
                    }
                }.awaitAll()
            }
            assertEquals(2, capturedCredentials.size)
            assertEquals(
                mapOf("credentials-a" to "Bearer qwen-secret-a", "credentials-b" to "Bearer qwen-secret-b"),
                capturedCredentials.toMap(),
            )
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
                        jsonBody("""{"content":"owned running turn","options":{"model":"${E2E_MODEL.alias}"}}""")
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
}
