package ru.souz.backend.e2e

import com.fasterxml.jackson.databind.JsonNode
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
import kotlin.time.Duration.Companion.seconds
import ru.souz.backend.common.BackendLlmSupport
import ru.souz.backend.config.BackendFeatureFlags
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
            assertTrue(
                health.jsonBody()["model"].asText() in BackendLlmSupport.chatModels.map { it.alias }
            )
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
            val persistedMessages = client.get(BackendHttpRoutes.chatMessages(chatId)) {
                trusted(userId)
            }

            assertEquals(HttpStatusCode.OK, list.status)
            assertEquals(chatId, list.jsonBody()["items"].single()["id"].asText())
            assertEquals(
                listOf("remember me", "assistant reply to remember me"),
                persistedMessages.jsonBody()["items"].map { it["content"].asText() },
            )

            val continued = client.post(BackendHttpRoutes.chatMessages(chatId)) {
                trusted(userId)
                jsonBody(
                    """{"content":"continue after restart","clientMessageId":"msg-2","options":{"model":"${E2E_LOCAL_MODEL.alias}"}}"""
                )
            }
            assertEquals(HttpStatusCode.OK, continued.status)
            val messages = eventually("continued execution after restart", timeout = 10.seconds) {
                client.get(BackendHttpRoutes.chatMessages(chatId)) {
                    trusted(userId)
                }.jsonBody()["items"].takeIf { it.size() == 4 }
            }
            assertEquals(listOf(1L, 2L, 3L, 4L), messages.map { it["seq"].asLong() })
            assertEquals("assistant", messages.last()["role"].asText())
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

    @Test
    fun `bootstrap and onboarding expose effective capabilities and persist completion`() =
        backendE2eTest("e2e_onboarding") {
            val userId = "onboarding-user"
            val bootstrap = client.get(BackendHttpRoutes.BOOTSTRAP) {
                trusted(userId)
            }
            val initial = client.get(BackendHttpRoutes.ONBOARDING_STATE) {
                trusted(userId)
            }

            assertEquals(HttpStatusCode.OK, bootstrap.status)
            assertEquals(userId, bootstrap.jsonBody()["user"]["id"].asText())
            val models = bootstrap.jsonBody()["capabilities"]["models"]
            assertTrue(models.any { model ->
                model["model"].asText() == E2E_LOCAL_MODEL.alias && model["serverManagedKey"].asBoolean()
            })
            assertFalse(models.any { it["provider"].asText() == "giga" })
            assertTrue(initial.jsonBody()["required"].asBoolean())
            assertFalse(initial.jsonBody()["completed"].asBoolean())
            assertEquals("preferences", initial.jsonBody()["currentStep"].asText())
            assertTrue(initial.jsonBody()["hasUsableModelAccess"].asBoolean())

            val complete = client.post(BackendHttpRoutes.ONBOARDING_COMPLETE) {
                trusted(userId)
                jsonBody(
                    """
                    {
                      "defaultModel": "${E2E_LOCAL_MODEL.alias}",
                      "locale": "iw-IL",
                      "timeZone": "Europe/Amsterdam",
                      "enabledTools": [],
                      "streamingMessages": true,
                      "interfaceLanguage": "en",
                      "requestTimeoutMillis": 45000,
                      "useFewShotExamples": false
                    }
                    """.trimIndent()
                )
            }
            val completed = client.get(BackendHttpRoutes.ONBOARDING_STATE) {
                trusted(userId)
            }.jsonBody()

            assertEquals(HttpStatusCode.OK, complete.status)
            assertTrue(complete.jsonBody()["completed"].asBoolean())
            assertFalse(completed["required"].asBoolean())
            assertTrue(completed["completed"].asBoolean())
            assertEquals("done", completed["currentStep"].asText())
            assertEquals("he-IL", completed["currentSettings"]["locale"].asText())
            assertEquals("Europe/Amsterdam", completed["currentSettings"]["timeZone"].asText())
            assertEquals("en", completed["currentSettings"]["interfaceLanguage"].asText())
            assertEquals(45_000L, completed["currentSettings"]["requestTimeoutMillis"].asLong())
            assertFalse(completed["currentSettings"]["useFewShotExamples"].asBoolean())
        }

    @Test
    fun `onboarding validation rejects malformed preferences without partial completion`() =
        backendE2eTest("e2e_onboarding_validation") {
            val invalidBodies = listOf(
                "unknown model" to """{"defaultModel":"unknown-model"}""",
                "Giga model" to """{"defaultModel":"GigaChat-Max"}""",
                "unsafe tool" to """{"enabledTools":["OpenBrowser"]}""",
                "locale" to """{"locale":"not-a-locale"}""",
                "time zone" to """{"timeZone":"Mars/Phobos"}""",
                "request timeout" to """{"requestTimeoutMillis":999}""",
            )

            invalidBodies.forEachIndexed { index, (description, body) ->
                val userId = "invalid-onboarding-$index"
                val before = client.get(BackendHttpRoutes.ONBOARDING_STATE) {
                    trusted(userId)
                }.jsonBody()["currentSettings"]
                val response = client.post(BackendHttpRoutes.ONBOARDING_COMPLETE) {
                    trusted(userId)
                    jsonBody(body)
                }
                val after = client.get(BackendHttpRoutes.ONBOARDING_STATE) {
                    trusted(userId)
                }.jsonBody()

                assertEquals(HttpStatusCode.BadRequest, response.status, description)
                assertEquals("invalid_request", response.jsonBody()["error"]["code"].asText(), description)
                assertFalse(after["completed"].asBoolean(), description)
                val afterSettings = after["currentSettings"]
                assertEquals(
                    before.properties().asSequence()
                        .filter { it.key != "enabledTools" }
                        .associate { it.key to it.value },
                    afterSettings.properties().asSequence()
                        .filter { it.key != "enabledTools" }
                        .associate { it.key to it.value },
                    description,
                )
                assertEquals(
                    before["enabledTools"].map { it.asText() }.toSet(),
                    afterSettings["enabledTools"].map { it.asText() }.toSet(),
                    description,
                )
            }
        }

    @Test
    fun `OpenAPI inventory and proxy security are exact with Telegram disabled and enabled`() {
        val expectedWithoutTelegram = linkedMapOf(
            "/" to setOf("get"),
            "/health" to setOf("get"),
            "/v1/bootstrap" to setOf("get"),
            "/v1/onboarding/state" to setOf("get"),
            "/v1/onboarding/complete" to setOf("post"),
            "/v1/me/settings" to setOf("get", "patch"),
            "/v1/me/provider-keys" to setOf("get"),
            "/v1/me/provider-keys/{provider}" to setOf("put", "delete"),
            "/v1/chats" to setOf("get", "post"),
            "/v1/chats/{chatId}/threads/{threadId}" to setOf("get"),
            "/v1/chats/{chatId}/title" to setOf("patch"),
            "/v1/chats/{chatId}/archive" to setOf("post"),
            "/v1/chats/{chatId}/unarchive" to setOf("post"),
            "/v1/chats/{chatId}/messages" to setOf("get", "post"),
            "/v1/chats/{chatId}/events" to setOf("get"),
            "/v1/chats/{chatId}/cancel-active" to setOf("post"),
            "/v1/chats/{chatId}/executions/{executionId}/cancel" to setOf("post"),
            "/v1/options/{optionId}/answer" to setOf("post"),
        )
        listOf(false, true).forEach { telegramEnabled ->
            backendE2eTest(
                schemaPrefix = "e2e_openapi_${if (telegramEnabled) "telegram" else "base"}",
                featureFlags = BackendFeatureFlags(telegramBot = telegramEnabled),
                telegramApi = FakeCompositionTelegramApi.takeIf { telegramEnabled },
            ) {
                val docs = client.get(BackendHttpRoutes.DOCS)
                val document = client.get(BackendHttpRoutes.OPENAPI_DOCUMENT).jsonBody()
                val expected = expectedWithoutTelegram + if (telegramEnabled) {
                    mapOf("/v1/chats/{chatId}/telegram-bot" to setOf("get", "put", "delete"))
                } else {
                    emptyMap()
                }
                val methods = setOf("get", "post", "put", "patch", "delete")
                val actual = document["paths"].properties().associate { (path, item) ->
                    path to item.fieldNames().asSequence().filter(methods::contains).toSet()
                }

                assertEquals(HttpStatusCode.OK, docs.status)
                assertTrue(docs.bodyAsText().contains("url: '/docs/openapi.json'"))
                assertEquals(expected, actual)
                assertFalse(actual.containsKey(BackendHttpRoutes.CHAT_WS_PATTERN))
                assertEquals(
                    setOf("souzProxyAuth", "souzUserIdentity"),
                    document["components"]["securitySchemes"].fieldNames().asSequence().toSet(),
                )
                document["paths"].properties().forEach { (path, pathItem) ->
                    pathItem.properties().asSequence()
                        .filter { it.key in methods }
                        .forEach { (method, operation) ->
                            val publicOperation =
                                path == "/" || path == "/health" ||
                                    (path == "/v1/chats" && method == "post") ||
                                    (path == "/v1/chats/{chatId}/threads/{threadId}" && method == "get")
                            if (publicOperation) {
                                assertFalse(operation.has("security"), "$method $path")
                            } else {
                                assertEquals(
                                    setOf("souzProxyAuth", "souzUserIdentity"),
                                    operation["security"][0].fieldNames().asSequence().toSet(),
                                    "$method $path",
                                )
                            }
                        }
                }
            }
        }
    }

    @Test
    fun `OpenAPI contract documents parameters errors nullable fields and write-only secrets`() =
        backendE2eTest(
            schemaPrefix = "e2e_openapi_contract",
            featureFlags = BackendFeatureFlags(telegramBot = true),
            telegramApi = FakeCompositionTelegramApi,
        ) {
            val document = client.get(BackendHttpRoutes.OPENAPI_DOCUMENT).jsonBody()
            val schemes = document["components"]["securitySchemes"]
            assertEquals("apiKey", schemes["souzProxyAuth"]["type"].asText())
            assertEquals("header", schemes["souzProxyAuth"]["in"].asText())
            assertEquals("X-Souz-Proxy-Auth", schemes["souzProxyAuth"]["name"].asText())
            assertEquals("apiKey", schemes["souzUserIdentity"]["type"].asText())
            assertEquals("header", schemes["souzUserIdentity"]["in"].asText())
            assertEquals("X-User-Id", schemes["souzUserIdentity"]["name"].asText())

            val putProviderKey = document.operation(BackendHttpRoutes.PROVIDER_KEY_PATTERN, "put")
            assertEquals("putProviderKey", putProviderKey["operationId"].asText())
            assertProxySecurity(putProviderKey)
            val providerParameter = putProviderKey.pathParameter("provider")
            assertEquals(
                BackendLlmSupport.userManagedKeyProviders.map { it.name.lowercase() }.toSet(),
                providerParameter["schema"]["enum"].map { it.asText() }.toSet(),
            )
            val providerKeyRequest = document.resolveSchema(putProviderKey.jsonRequestSchema())
            assertEquals(listOf("apiKey"), providerKeyRequest.requiredNames())
            val apiKey = providerKeyRequest["properties"]["apiKey"]
            assertTrue(apiKey["writeOnly"].asBoolean())
            assertEquals(1, apiKey["minLength"].asInt())
            assertEquals("\\S", apiKey["pattern"].asText())

            val putTelegram = document.operation(BackendHttpRoutes.CHAT_TELEGRAM_BOT_PATTERN, "put")
            assertEquals("upsertTelegramBotBinding", putTelegram["operationId"].asText())
            assertProxySecurity(putTelegram)
            assertUuidPathParameter(putTelegram, "chatId")
            val telegramRequest = document.resolveSchema(putTelegram.jsonRequestSchema())
            assertEquals(listOf("token"), telegramRequest.requiredNames())
            val telegramToken = telegramRequest["properties"]["token"]
            assertTrue(telegramToken["writeOnly"].asBoolean())
            assertEquals(1, telegramToken["minLength"].asInt())
            assertEquals(4096, telegramToken["maxLength"].asInt())

            val createMessage = document.operation(BackendHttpRoutes.CHAT_MESSAGES_PATTERN, "post")
            assertEquals("createChatMessage", createMessage["operationId"].asText())
            assertProxySecurity(createMessage)
            assertUuidPathParameter(createMessage, "chatId")
            val createMessageRequest = document.resolveSchema(createMessage.jsonRequestSchema())
            assertEquals(listOf("content"), createMessageRequest.requiredNames())
            assertEquals("\\S", createMessageRequest["properties"]["content"]["pattern"].asText())
            val createMessageResponse = document.resolveSchema(createMessage.jsonResponseSchema("200"))
            assertFalse("assistantMessage" in createMessageResponse.requiredNames())
            assertTrue(createMessageResponse["properties"]["assistantMessage"].allowsNull())

            val listMessages = document.operation(BackendHttpRoutes.CHAT_MESSAGES_PATTERN, "get")
            assertUuidPathParameter(listMessages, "chatId")
            assertEquals(1.0, listMessages.queryParameter("limit")["schema"]["minimum"].asDouble())
            val messagesResponse = document.resolveSchema(listMessages.jsonResponseSchema("200"))
            assertFalse("nextBeforeSeq" in messagesResponse.requiredNames())
            assertTrue(messagesResponse["properties"]["nextBeforeSeq"].allowsNull())

            val cancelExecution = document.operation(BackendHttpRoutes.CHAT_EXECUTION_CANCEL_PATTERN, "post")
            assertEquals("cancelExecution", cancelExecution["operationId"].asText())
            assertProxySecurity(cancelExecution)
            assertUuidPathParameter(cancelExecution, "chatId")
            assertUuidPathParameter(cancelExecution, "executionId")
            assertV1ErrorSchema(document, cancelExecution, "404")

            val listEvents = document.operation(BackendHttpRoutes.CHAT_EVENTS_PATTERN, "get")
            assertEquals("listChatEvents", listEvents["operationId"].asText())
            assertProxySecurity(listEvents)
            assertUuidPathParameter(listEvents, "chatId")
            assertEquals(0.0, listEvents.queryParameter("afterSeq")["schema"]["minimum"].asDouble())
            assertEquals("array", document.resolveSchema(listEvents.jsonResponseSchema("200"))["properties"]["items"]["type"].asText())

            val createChat = document.operation(BackendHttpRoutes.CHATS, "post")
            assertFalse(createChat.has("security"))
            assertEquals("createClientChat", createChat["operationId"].asText())
            assertV1ErrorSchema(document, createChat, "409")
        }

    private fun JsonNode.operation(path: String, method: String): JsonNode =
        assertNotNull(this["paths"][path][method], "$method $path")

    private fun JsonNode.jsonRequestSchema(): JsonNode =
        this["requestBody"]["content"]["application/json"]["schema"]

    private fun JsonNode.jsonResponseSchema(status: String): JsonNode =
        this["responses"][status]["content"]["application/json"]["schema"]

    private fun JsonNode.pathParameter(name: String): JsonNode =
        parameter(name, "path")

    private fun JsonNode.queryParameter(name: String): JsonNode =
        parameter(name, "query")

    private fun JsonNode.parameter(name: String, location: String): JsonNode =
        assertNotNull(this["parameters"].firstOrNull { parameter ->
            parameter["name"].asText() == name && parameter["in"].asText() == location
        }, "$location parameter $name")

    private fun JsonNode.resolveSchema(schema: JsonNode): JsonNode {
        val ref = schema[$$"$ref"]?.asText() ?: return schema
        val name = ref.removePrefix("#/components/schemas/")
        return assertNotNull(this["components"]["schemas"][name], ref)
    }

    private fun JsonNode.requiredNames(): List<String> =
        this["required"]?.map { it.asText() }.orEmpty()

    private fun JsonNode.allowsNull(): Boolean =
        when {
            this["type"]?.isTextual == true && this["type"].asText() == "null" -> true
            this["type"]?.isArray == true && this["type"].any { it.asText() == "null" } -> true
            this["anyOf"]?.any { it.allowsNull() } == true -> true
            this["oneOf"]?.any { it.allowsNull() } == true -> true
            else -> false
        }

    private fun assertProxySecurity(operation: JsonNode) {
        assertEquals(
            setOf("souzProxyAuth", "souzUserIdentity"),
            operation["security"][0].fieldNames().asSequence().toSet(),
        )
    }

    private fun assertUuidPathParameter(operation: JsonNode, name: String) {
        val parameter = operation.pathParameter(name)
        assertTrue(parameter["required"].asBoolean(), name)
        assertEquals("string", parameter["schema"]["type"].asText(), name)
        assertEquals("uuid", parameter["schema"]["format"].asText(), name)
    }

    private fun assertV1ErrorSchema(document: JsonNode, operation: JsonNode, status: String) {
        val errorEnvelope = document.resolveSchema(operation.jsonResponseSchema(status))
        val error = document.resolveSchema(errorEnvelope["properties"]["error"])
        assertEquals(setOf("code", "message"), error["properties"].fieldNames().asSequence().toSet())
    }
}

private object FakeCompositionTelegramApi : ru.souz.backend.telegram.TelegramBotApi {
    override suspend fun getMe(token: String) = ru.souz.backend.telegram.TelegramGetMeResponse(ok = true)

    override suspend fun getUpdates(
        token: String,
        offset: Long?,
        timeoutSeconds: Int,
        allowedUpdates: List<String>,
    ) = ru.souz.backend.telegram.TelegramUpdatesResponse(ok = true)

    override suspend fun sendMessage(token: String, chatId: Long, text: String) = Unit

    override suspend fun sendChatAction(token: String, chatId: Long, action: String) = Unit

    override suspend fun deleteWebhook(token: String, dropPendingUpdates: Boolean) = Unit
}
