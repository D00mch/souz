package ru.souz.backend.e2e

import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.llms.LLMModel

class BackendCompositionE2eTest {
    @Test
    fun `production graph exposes health docs feature inventory and enforces trusted proxy`() =
        backendE2eTest("e2e_composition") {
            val root = client.get(BackendHttpRoutes.ROOT)
            val health = client.get(BackendHttpRoutes.HEALTH)
            val openApi = client.get(BackendHttpRoutes.OPENAPI_DOCUMENT)
            val unauthorized = client.get(BackendHttpRoutes.SETTINGS)
            val trusted = client.get(BackendHttpRoutes.SETTINGS) {
                trusted("proxy-user")
            }

            assertEquals(HttpStatusCode.OK, root.status)
            assertEquals(HttpStatusCode.OK, health.status)
            assertEquals(LLMModel.OpenAIGpt52.alias, health.jsonBody()["model"].asText())
            assertEquals(HttpStatusCode.OK, openApi.status)
            assertTrue(openApi.jsonBody()["paths"].has(BackendHttpRoutes.SETTINGS))
            assertFalse(root.jsonBody()["endpoints"].any { it.asText().contains("telegram-bot") })
            assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)
            assertEquals("untrusted_proxy", unauthorized.jsonBody()["error"]["code"].asText())
            assertEquals(HttpStatusCode.OK, trusted.status)
        }

    @Test
    fun `settings and provider keys go through production services and encrypted Postgres`() =
        backendE2eTest("e2e_settings_keys") {
            val patch = client.patch(BackendHttpRoutes.SETTINGS) {
                trusted("settings-user")
                jsonBody(
                    """
                    {
                      "defaultModel": "${E2E_LOCAL_MODEL.alias}",
                      "locale": "iw-IL",
                      "timeZone": "Europe/Amsterdam",
                      "streamingMessages": true,
                      "enabledTools": []
                    }
                    """.trimIndent()
                )
            }
            val putKey = client.put(BackendHttpRoutes.providerKey("qwen")) {
                trusted("settings-user")
                jsonBody("""{"apiKey":"secret-qwen-key"}""")
            }
            val listed = client.get(BackendHttpRoutes.PROVIDER_KEYS) {
                trusted("settings-user")
            }

            assertEquals(HttpStatusCode.OK, patch.status)
            val settings = patch.jsonBody()["settings"]
            assertEquals(E2E_LOCAL_MODEL.alias, settings["defaultModel"].asText())
            assertEquals("he-IL", settings["locale"].asText())
            assertEquals("Europe/Amsterdam", settings["timeZone"].asText())
            assertEquals(HttpStatusCode.OK, putKey.status)
            assertEquals("qwen", putKey.jsonBody()["providerKey"]["provider"].asText())
            assertEquals(HttpStatusCode.OK, listed.status)
            assertEquals(1, listed.jsonBody()["items"].size())
            assertFalse(listed.bodyAsText().contains("secret-qwen-key"))
            assertFalse(
                sql { connection ->
                    connection.prepareStatement(
                        "select encode(encrypted_api_key, 'escape') from user_provider_keys where user_id = ?"
                    ).use { statement ->
                        statement.setString(1, "settings-user")
                        statement.executeQuery().use { rows ->
                            rows.next()
                            rows.getString(1).contains("secret-qwen-key")
                        }
                    }
                }
            )
        }

    @Test
    fun `public and proxy chat state survives application restart on the same schema`() {
        val schema = ru.souz.backend.storage.postgres.newPostgresSchema("e2e_restart")
        val userId = UUID.randomUUID().toString()
        lateinit var chatId: String

        backendE2eTest(schemaPrefix = "e2e_restart_first", schema = schema) {
            val created = client.post(BackendHttpRoutes.CHATS) {
                jsonBody("""{"userId":"$userId","requestId":"create-1","clientType":"backend","title":" Restart "}""")
            }
            assertEquals(HttpStatusCode.Created, created.status)
            chatId = created.jsonBody()["chat"]["id"].asText()

            val sent = client.post(BackendHttpRoutes.chatMessages(chatId)) {
                trusted(userId)
                jsonBody(
                    """
                    {
                      "content": "remember me",
                      "clientMessageId": "msg-1",
                      "options": {"model": "${E2E_LOCAL_MODEL.alias}"}
                    }
                    """.trimIndent()
                )
            }
            assertEquals(HttpStatusCode.OK, sent.status)
            val persistedMessages = eventually("completed execution") {
                client.get(BackendHttpRoutes.chatMessages(chatId)) {
                    trusted(userId)
                }.jsonBody()["items"].takeIf { it.size() == 2 }
            }
            assertEquals(2, persistedMessages.size())
        }

        backendE2eTest(schemaPrefix = "e2e_restart_second", schema = schema) {
            val list = client.get(BackendHttpRoutes.CHATS) {
                trusted(userId)
            }
            val messages = client.get(BackendHttpRoutes.chatMessages(chatId)) {
                trusted(userId)
            }

            assertEquals(HttpStatusCode.OK, list.status)
            assertEquals(chatId, list.jsonBody()["items"].single()["id"].asText())
            assertEquals(
                listOf("remember me", "assistant reply to remember me"),
                messages.jsonBody()["items"].map { it["content"].asText() },
            )
        }
    }

    @Test
    fun `settings affect the next real-kernel local turn`() =
        backendE2eTest("e2e_settings_turn") {
            val userId = UUID.randomUUID().toString()
            val created = client.post(BackendHttpRoutes.CHATS) {
                jsonBody("""{"userId":"$userId","requestId":"create-1","clientType":"backend"}""")
            }
            val chatId = created.jsonBody()["chat"]["id"].asText()
            val patched = client.patch(BackendHttpRoutes.SETTINGS) {
                trusted(userId)
                jsonBody("""{"defaultModel":"${LLMModel.LocalGemma4_E2B_It.alias}"}""")
            }

            assertEquals(HttpStatusCode.BadRequest, patched.status)

            client.patch(BackendHttpRoutes.SETTINGS) {
                trusted(userId)
                jsonBody("""{"defaultModel":"${E2E_LOCAL_MODEL.alias}","temperature":0.2}""")
            }
            val sent = client.post(BackendHttpRoutes.chatMessages(chatId)) {
                trusted(userId)
                jsonBody("""{"content":"use current settings"}""")
            }

            assertEquals(HttpStatusCode.OK, sent.status)
            eventually("captured LLM request") { llm.requests.singleOrNull() }
            assertEquals(E2E_LOCAL_MODEL.alias, llm.requests.single().model)
            assertEquals(0.2f, llm.requests.single().temperature)
        }
}
