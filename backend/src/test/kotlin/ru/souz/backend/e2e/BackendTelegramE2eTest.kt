package ru.souz.backend.e2e

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.telegram.TelegramBotApi
import ru.souz.backend.telegram.TelegramGetMeResponse
import ru.souz.backend.telegram.TelegramUpdatesResponse
import ru.souz.backend.telegram.TelegramUser

class BackendTelegramE2eTest {
    @Test
    fun `telegram routes validate redact encrypt and enforce chat ownership`() =
        backendE2eTest(
            schemaPrefix = "e2e_telegram",
            featureFlags = BackendFeatureFlags(wsEvents = true, telegramBot = true),
            telegramApi = FakeTelegramBotApi(),
        ) {
            val userId = UUID.randomUUID().toString()
            val foreignUserId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId, "create-1")
            val foreignChatId = createPublicChat(foreignUserId, "create-2")
            val token = "123456:valid-token"

            val invalid = client.put(BackendHttpRoutes.chatTelegramBot(chatId)) {
                trusted(userId)
                jsonBody("""{"token":"bad-token"}""")
            }
            val upserted = client.put(BackendHttpRoutes.chatTelegramBot(chatId)) {
                trusted(userId)
                jsonBody("""{"token":"$token"}""")
            }
            val fetched = client.get(BackendHttpRoutes.chatTelegramBot(chatId)) {
                trusted(userId)
            }
            val foreign = client.get(BackendHttpRoutes.chatTelegramBot(chatId)) {
                trusted(foreignUserId)
            }
            val conflict = client.put(BackendHttpRoutes.chatTelegramBot(foreignChatId)) {
                trusted(foreignUserId)
                jsonBody("""{"token":"$token"}""")
            }

            assertEquals(HttpStatusCode.BadRequest, invalid.status)
            assertEquals("invalid_telegram_bot_token", invalid.jsonBody()["error"]["code"].asText())
            assertEquals(HttpStatusCode.OK, upserted.status)
            val upsertPayload = upserted.jsonBody()
            assertEquals("souze2ebot", upsertPayload["telegramBot"]["botUsername"].asText())
            assertTrue(upsertPayload["pendingLinkCommand"].asText().startsWith("/start "))
            assertFalse(upserted.bodyAsText().contains(token))
            assertEquals(HttpStatusCode.OK, fetched.status)
            assertFalse(fetched.bodyAsText().contains(token))
            assertEquals(false, fetched.jsonBody()["telegramBot"]["linked"].asBoolean())
            assertEquals(HttpStatusCode.NotFound, foreign.status)
            assertEquals(HttpStatusCode.Conflict, conflict.status)
            assertEquals("telegram_bot_already_bound", conflict.jsonBody()["error"]["code"].asText())

            assertFalse(
                sql { connection ->
                    connection.prepareStatement(
                        "select bot_token_encrypted from telegram_bot_bindings where chat_id = ?"
                    ).use { statement ->
                        statement.setObject(1, UUID.fromString(chatId))
                        statement.executeQuery().use { rows ->
                            rows.next()
                            rows.getString(1).contains(token)
                        }
                    }
                }
            )
        }

    private suspend fun BackendE2eScope.createPublicChat(userId: String, requestId: String): String {
        val created = client.post(BackendHttpRoutes.CHATS) {
            jsonBody("""{"userId":"$userId","requestId":"$requestId","clientType":"backend"}""")
        }
        assertEquals(HttpStatusCode.Created, created.status)
        return created.jsonBody()["chat"]["id"].asText()
    }
}

private class FakeTelegramBotApi : TelegramBotApi {
    override suspend fun getMe(token: String): TelegramGetMeResponse =
        if (token.startsWith("bad")) {
            TelegramGetMeResponse(ok = false, errorCode = 401, description = "Unauthorized")
        } else {
            TelegramGetMeResponse(
                ok = true,
                result = TelegramUser(id = 123456L, isBot = true, firstName = "Souz E2E", username = "souze2ebot"),
            )
        }

    override suspend fun getUpdates(
        token: String,
        offset: Long?,
        timeoutSeconds: Int,
        allowedUpdates: List<String>,
    ): TelegramUpdatesResponse = TelegramUpdatesResponse(ok = true)

    override suspend fun sendMessage(token: String, chatId: Long, text: String) = Unit

    override suspend fun sendChatAction(token: String, chatId: Long, action: String) = Unit

    override suspend fun deleteWebhook(token: String, dropPendingUpdates: Boolean) = Unit
}
