package ru.souz.backend.http

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.telegram.TelegramBotApi
import ru.souz.backend.telegram.TelegramBotBindingService
import ru.souz.backend.telegram.TelegramBotTokenCrypto
import ru.souz.backend.telegram.TelegramChat
import ru.souz.backend.telegram.TelegramGetMeResponse
import ru.souz.backend.telegram.TelegramMessage
import ru.souz.backend.telegram.TelegramResponseParameters
import ru.souz.backend.telegram.TelegramUpdate
import ru.souz.backend.telegram.TelegramUpdatesResponse
import ru.souz.backend.telegram.TelegramUser
import ru.souz.backend.telegram.sha256Hex
import ru.souz.backend.testutil.repository.MemoryTelegramBotBindingRepository

class BackendTelegramRouteTest {
    private val json = jacksonObjectMapper()

    @Test
    fun `telegram bot routes are absent when backend telegram feature is disabled`() = testApplication {
        val context = telegramRouteTestContext(
            base = routeTestContext(
                featureFlags = BackendFeatureFlags(
                    streamingMessages = false,
                    toolEvents = true,
                    telegramBot = false,
                )
            )
        )
        val chat = chat(userId = "user-a", title = "Owned")
        runBlocking {
            context.base.chatRepository.create(chat)
        }
        application {
            backendApplication(
                BackendHttpDependencies(
                    bootstrapService = context.base.bootstrapService,
                    onboardingService = context.base.onboardingService,
                    userSettingsService = context.base.userSettingsService,
                    providerKeyService = context.base.userProviderKeyService,
                    chatService = context.base.chatService,
                    messageService = context.base.messageService,
                    executionService = context.base.executionService,
                    optionService = context.base.optionService,
                    eventService = context.base.eventService,
                    featureFlags = context.base.featureFlags,
                    selectedModel = { context.base.settingsProvider.gigaModel.alias },
                    trustedProxyToken = { "proxy-secret" },
                )
            )
        }

        val response = client.get(BackendHttpRoutes.chatTelegramBot(chat.id)) {
            trustedHeaders("user-a")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `get telegram bot returns null for owned chat without binding`() = testApplication {
        val context = telegramRouteTestContext()
        val chat = chat(userId = "user-a", title = "Bound chat")
        runBlocking {
            context.base.chatRepository.create(chat)
        }
        installTelegramApplication(context)

        val response = client.get(BackendHttpRoutes.chatTelegramBot(chat.id)) {
            trustedHeaders("user-a")
        }
        val payload = json.readTree(response.bodyAsText())

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(payload["telegramBot"].isNull)
    }

    @Test
    fun `get telegram bot returns chat not found for missing or foreign chat`() = testApplication {
        val context = telegramRouteTestContext()
        val foreignChat = chat(userId = "user-b", title = "Foreign")
        runBlocking {
            context.base.chatRepository.create(foreignChat)
        }
        installTelegramApplication(context)

        val response = client.get(BackendHttpRoutes.chatTelegramBot(foreignChat.id)) {
            trustedHeaders("user-a")
        }
        val payload = json.readTree(response.bodyAsText())

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("chat_not_found", payload["error"]["code"].asText())
    }

    @Test
    fun `put telegram bot stores binding returns pending link command and never returns token`() = testApplication {
        val context = telegramRouteTestContext()
        val chat = chat(userId = "user-a", title = "Owned")
        context.telegramApi.allowToken("123456:valid-token")
        runBlocking {
            context.base.chatRepository.create(chat)
        }
        installTelegramApplication(context)

        val response = client.put(BackendHttpRoutes.chatTelegramBot(chat.id)) {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"token":" 123456:valid-token "}""")
        }
        val payload = json.readTree(response.bodyAsText())
        val stored = runBlocking { context.repository.getByChat(chat.id) }
        val pendingLinkCommand = payload["pendingLinkCommand"].asText()
        val linkSecret = pendingLinkCommand.removePrefix("/start").trim()

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(chat.id.toString(), payload["telegramBot"]["chatId"].asText())
        assertEquals(true, payload["telegramBot"]["enabled"].asBoolean())
        assertEquals("souz_bot", payload["telegramBot"]["botUsername"].asText())
        assertEquals("Souz", payload["telegramBot"]["botFirstName"].asText())
        assertNotNull(payload["telegramBot"]["createdAt"].asText())
        assertNotNull(payload["telegramBot"]["updatedAt"].asText())
        assertEquals(false, payload["telegramBot"]["linked"].asBoolean())
        assertTrue(payload["telegramBot"]["telegramUsername"].isNull)
        assertTrue(payload["telegramBot"]["telegramFirstName"].isNull)
        assertTrue(payload["telegramBot"]["telegramLastName"].isNull)
        assertTrue(payload["telegramBot"]["linkedAt"].isNull)
        assertTrue(payload["telegramBot"]["botToken"] == null)
        assertTrue(payload["telegramBot"]["botTokenHash"] == null)
        assertTrue(payload["telegramBot"]["botTokenEncrypted"] == null)
        assertTrue(pendingLinkCommand.startsWith("/start "))
        assertTrue(linkSecret.isNotBlank())
        assertEquals("souz_bot", stored?.botUsername)
        assertEquals("Souz", stored?.botFirstName)
        assertTrue(stored?.botTokenEncrypted?.isNotBlank() == true)
        assertTrue(stored?.botTokenEncrypted != "123456:valid-token")
        assertEquals(sha256Hex(linkSecret), stored?.linkSecretHash)
        assertEquals(0L, stored?.lastUpdateId)
        assertEquals(listOf("123456:valid-token"), context.telegramApi.getMeCalls)
        assertEquals(listOf(DeleteWebhookCall("123456:valid-token", true)), context.telegramApi.deleteWebhookCalls)
    }

    @Test
    fun `get telegram bot returns linked false before first telegram private message`() = testApplication {
        val context = telegramRouteTestContext()
        val chat = chat(userId = "user-a", title = "Owned")
        context.telegramApi.allowToken("123456:valid-token")
        runBlocking {
            context.base.chatRepository.create(chat)
            context.service.upsert(
                userId = "user-a",
                chatId = chat.id,
                token = "123456:valid-token",
            )
        }
        installTelegramApplication(context)

        val response = client.get(BackendHttpRoutes.chatTelegramBot(chat.id)) {
            trustedHeaders("user-a")
        }
        val payload = json.readTree(response.bodyAsText())

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(false, payload["telegramBot"]["linked"].asBoolean())
        assertTrue(payload["telegramBot"]["telegramUsername"].isNull)
        assertTrue(payload["telegramBot"]["telegramFirstName"].isNull)
        assertTrue(payload["telegramBot"]["telegramLastName"].isNull)
        assertTrue(payload["telegramBot"]["linkedAt"].isNull)
        assertTrue(payload["telegramBot"]["telegramUserId"] == null)
        assertTrue(payload["telegramBot"]["telegramChatId"] == null)
        assertTrue(payload["pendingLinkCommand"].isNull)
    }

    @Test
    fun `put telegram bot with blank token returns bad request`() = testApplication {
        val context = telegramRouteTestContext()
        val chat = chat(userId = "user-a", title = "Owned")
        runBlocking {
            context.base.chatRepository.create(chat)
        }
        installTelegramApplication(context)

        val response = client.put(BackendHttpRoutes.chatTelegramBot(chat.id)) {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"token":"   "}""")
        }
        val payload = json.readTree(response.bodyAsText())

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("bad_request", payload["error"]["code"].asText())
    }

    @Test
    fun `put telegram bot with too long token returns bad request`() = testApplication {
        val context = telegramRouteTestContext()
        val chat = chat(userId = "user-a", title = "Owned")
        runBlocking {
            context.base.chatRepository.create(chat)
        }
        installTelegramApplication(context)

        val response = client.put(BackendHttpRoutes.chatTelegramBot(chat.id)) {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"token":"${"x".repeat(4097)}"}""")
        }
        val payload = json.readTree(response.bodyAsText())

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("bad_request", payload["error"]["code"].asText())
    }

    @Test
    fun `put telegram bot with unauthorized getMe returns invalid token`() = testApplication {
        val context = telegramRouteTestContext()
        val chat = chat(userId = "user-a", title = "Owned")
        context.telegramApi.rejectToken("123456:invalid-token", errorCode = 401, description = "Unauthorized")
        runBlocking {
            context.base.chatRepository.create(chat)
        }
        installTelegramApplication(context)

        val response = client.put(BackendHttpRoutes.chatTelegramBot(chat.id)) {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"token":"123456:invalid-token"}""")
        }
        val payload = json.readTree(response.bodyAsText())

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("invalid_telegram_bot_token", payload["error"]["code"].asText())
        assertNull(runBlocking { context.repository.getByChat(chat.id) })
    }

    @Test
    fun `put same token into same chat stays successful`() = testApplication {
        val context = telegramRouteTestContext()
        val chat = chat(userId = "user-a", title = "Owned")
        context.telegramApi.allowToken("123456:stable-token")
        runBlocking {
            context.base.chatRepository.create(chat)
        }
        installTelegramApplication(context)

        val firstResponse = client.put(BackendHttpRoutes.chatTelegramBot(chat.id)) {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"token":"123456:stable-token"}""")
        }
        val beforeSecond = runBlocking { context.repository.getByChat(chat.id) }
        runBlocking {
            context.repository.updateLastUpdateId(beforeSecond!!.id, lastUpdateId = 42L)
        }
        val secondResponse = client.put(BackendHttpRoutes.chatTelegramBot(chat.id)) {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"token":"123456:stable-token"}""")
        }
        val secondPayload = json.readTree(secondResponse.bodyAsText())
        val afterSecond = runBlocking { context.repository.getByChat(chat.id) }

        assertEquals(HttpStatusCode.OK, firstResponse.status)
        assertEquals(HttpStatusCode.OK, secondResponse.status)
        assertNotNull(beforeSecond)
        assertNotNull(afterSecond)
        assertEquals(beforeSecond.id, afterSecond?.id)
        assertEquals(0L, afterSecond?.lastUpdateId)
        assertTrue(secondPayload["pendingLinkCommand"].asText().startsWith("/start "))
        assertTrue(afterSecond.linkSecretHash != beforeSecond.linkSecretHash)
    }

    @Test
    fun `put same token into different chat returns already bound`() = testApplication {
        val context = telegramRouteTestContext()
        val firstChat = chat(userId = "user-a", title = "First")
        val secondChat = chat(userId = "user-a", title = "Second")
        context.telegramApi.allowToken("123456:shared-token")
        runBlocking {
            context.base.chatRepository.create(firstChat)
            context.base.chatRepository.create(secondChat)
        }
        installTelegramApplication(context)

        val firstResponse = client.put(BackendHttpRoutes.chatTelegramBot(firstChat.id)) {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"token":"123456:shared-token"}""")
        }
        val secondResponse = client.put(BackendHttpRoutes.chatTelegramBot(secondChat.id)) {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"token":"123456:shared-token"}""")
        }
        val payload = json.readTree(secondResponse.bodyAsText())

        assertEquals(HttpStatusCode.OK, firstResponse.status)
        assertEquals(HttpStatusCode.Conflict, secondResponse.status)
        assertEquals("telegram_bot_already_bound", payload["error"]["code"].asText())
    }

    @Test
    fun `put new token into same chat replaces hash and resets update id`() = testApplication {
        val context = telegramRouteTestContext()
        val chat = chat(userId = "user-a", title = "Owned")
        context.telegramApi.allowToken("123456:first-token")
        context.telegramApi.allowToken("123456:second-token")
        runBlocking {
            context.base.chatRepository.create(chat)
            context.repository.upsertForChat(
                userId = "user-a",
                chatId = chat.id,
                botToken = "123456:first-token",
                botTokenHash = "old-hash",
                linkSecretHash = "old-link-secret-hash",
                now = Instant.parse("2026-05-04T09:00:00Z"),
            )
            val binding = context.repository.getByChat(chat.id)!!
            context.repository.updateLastUpdateId(binding.id, 77L, Instant.parse("2026-05-04T09:05:00Z"))
        }
        installTelegramApplication(context)

        val response = client.put(BackendHttpRoutes.chatTelegramBot(chat.id)) {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"token":"123456:second-token"}""")
        }
        val payload = json.readTree(response.bodyAsText())
        val stored = runBlocking { context.repository.getByChat(chat.id) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(payload["pendingLinkCommand"].asText().startsWith("/start "))
        assertTrue(stored?.botTokenEncrypted?.isNotBlank() == true)
        assertTrue(stored?.botTokenEncrypted != "123456:second-token")
        assertEquals(0L, stored?.lastUpdateId)
        assertTrue(stored?.botTokenHash != "old-hash")
        assertTrue(stored?.linkSecretHash != "old-link-secret-hash")
    }

    @Test
    fun `delete telegram bot returns null for existing binding`() = testApplication {
        val context = telegramRouteTestContext()
        val chat = chat(userId = "user-a", title = "Owned")
        runBlocking {
            context.base.chatRepository.create(chat)
            context.repository.upsertForChat(
                userId = "user-a",
                chatId = chat.id,
                botToken = "123456:token",
                botTokenHash = "hash",
                linkSecretHash = "link-hash",
                now = Instant.parse("2026-05-04T09:00:00Z"),
            )
        }
        installTelegramApplication(context)

        val response = client.delete(BackendHttpRoutes.chatTelegramBot(chat.id)) {
            trustedHeaders("user-a")
        }
        val payload = json.readTree(response.bodyAsText())

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(payload["telegramBot"].isNull)
        assertNull(runBlocking { context.repository.getByChat(chat.id) })
    }

    @Test
    fun `delete telegram bot is idempotent for missing binding`() = testApplication {
        val context = telegramRouteTestContext()
        val chat = chat(userId = "user-a", title = "Owned")
        runBlocking {
            context.base.chatRepository.create(chat)
        }
        installTelegramApplication(context)

        val response = client.delete(BackendHttpRoutes.chatTelegramBot(chat.id)) {
            trustedHeaders("user-a")
        }
        val payload = json.readTree(response.bodyAsText())

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(payload["telegramBot"].isNull)
    }

    @Test
    fun `delete telegram bot returns chat not found for foreign chat`() = testApplication {
        val context = telegramRouteTestContext()
        val foreignChat = chat(userId = "user-b", title = "Foreign")
        runBlocking {
            context.base.chatRepository.create(foreignChat)
        }
        installTelegramApplication(context)

        val response = client.delete(BackendHttpRoutes.chatTelegramBot(foreignChat.id)) {
            trustedHeaders("user-a")
        }
        val payload = json.readTree(response.bodyAsText())

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("chat_not_found", payload["error"]["code"].asText())
    }
}

private data class TelegramRouteTestContext(
    val base: RouteTestContext,
    val repository: MemoryTelegramBotBindingRepository,
    val telegramApi: FakeTelegramBotApi,
    val service: TelegramBotBindingService,
)

private fun ApplicationTestBuilder.installTelegramApplication(context: TelegramRouteTestContext) {
    application {
        backendApplication(
            BackendHttpDependencies(
                bootstrapService = context.base.bootstrapService,
                onboardingService = context.base.onboardingService,
                userSettingsService = context.base.userSettingsService,
                providerKeyService = context.base.userProviderKeyService,
                chatService = context.base.chatService,
                messageService = context.base.messageService,
                executionService = context.base.executionService,
                optionService = context.base.optionService,
                eventService = context.base.eventService,
                featureFlags = context.base.featureFlags,
                selectedModel = { context.base.settingsProvider.gigaModel.alias },
                trustedProxyToken = { "proxy-secret" },
                telegramBotBindingService = context.service,
            )
        )
    }
}

private fun telegramRouteTestContext(
    base: RouteTestContext = routeTestContext(
        featureFlags = BackendFeatureFlags(
            streamingMessages = false,
            toolEvents = true,
            telegramBot = true,
        )
    ),
): TelegramRouteTestContext {
    val repository = MemoryTelegramBotBindingRepository()
    val telegramApi = FakeTelegramBotApi()
    val service = TelegramBotBindingService(
        chatRepository = base.chatRepository,
        bindingRepository = repository,
        telegramBotApi = telegramApi,
        tokenCrypto = TelegramBotTokenCrypto(TEST_TELEGRAM_TOKEN_ENCRYPTION_KEY),
        clock = Clock.fixed(Instant.parse("2026-05-04T10:00:00Z"), ZoneOffset.UTC),
    )
    return TelegramRouteTestContext(
        base = base,
        repository = repository,
        telegramApi = telegramApi,
        service = service,
    )
}

private class FakeTelegramBotApi : TelegramBotApi {
    val getMeCalls = mutableListOf<String>()
    val deleteWebhookCalls = mutableListOf<DeleteWebhookCall>()

    private val responses = LinkedHashMap<String, TelegramGetMeResponse>()

    fun allowToken(token: String) {
        responses[token] = TelegramGetMeResponse(
            ok = true,
            result = TelegramUser(
                id = 42L,
                isBot = true,
                firstName = "Souz",
                lastName = "Assistant",
                username = "souz_bot",
            ),
        )
    }

    fun rejectToken(
        token: String,
        errorCode: Int,
        description: String,
    ) {
        responses[token] = TelegramGetMeResponse(
            ok = false,
            description = description,
            errorCode = errorCode,
            parameters = TelegramResponseParameters(),
        )
    }

    override suspend fun getMe(token: String): TelegramGetMeResponse {
        getMeCalls += token
        return responses[token]
            ?: TelegramGetMeResponse(
                ok = false,
                description = "Unauthorized",
                errorCode = 401,
                parameters = TelegramResponseParameters(),
            )
    }

    override suspend fun getUpdates(
        token: String,
        offset: Long?,
        timeoutSeconds: Int,
        allowedUpdates: List<String>,
    ): TelegramUpdatesResponse = TelegramUpdatesResponse(ok = true, result = emptyList())

    override suspend fun deleteWebhook(
        token: String,
        dropPendingUpdates: Boolean,
    ) {
        deleteWebhookCalls += DeleteWebhookCall(token, dropPendingUpdates)
    }

    override suspend fun sendMessage(
        token: String,
        chatId: Long,
        text: String,
    ) = Unit
}

private data class DeleteWebhookCall(
    val token: String,
    val dropPendingUpdates: Boolean,
)

private const val TEST_TELEGRAM_TOKEN_ENCRYPTION_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
