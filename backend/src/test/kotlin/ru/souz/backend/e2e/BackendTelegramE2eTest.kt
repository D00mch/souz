package ru.souz.backend.e2e

import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.telegram.TelegramBotApi
import ru.souz.backend.telegram.TelegramChat
import ru.souz.backend.telegram.TelegramGetMeResponse
import ru.souz.backend.telegram.TelegramMessage
import ru.souz.backend.telegram.TelegramUpdate
import ru.souz.backend.telegram.TelegramUpdatesResponse
import ru.souz.backend.telegram.TelegramUser
import ru.souz.llms.LLMMessageRole

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

    @Test
    fun `polling links once rejects foreign senders and executes each update once`() {
        val telegramApi = FakeTelegramBotApi()
        backendE2eTest(
            schemaPrefix = "e2e_telegram_polling",
            featureFlags = BackendFeatureFlags(wsEvents = true, telegramBot = true),
            telegramApi = telegramApi,
            startBackgroundServices = true,
        ) {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId, "create-polling")
            val settings = client.patch(BackendHttpRoutes.SETTINGS) {
                trusted(userId)
                jsonBody("""{"defaultModel":"${E2E_LOCAL_MODEL.alias}"}""")
            }
            assertEquals(HttpStatusCode.OK, settings.status)
            val token = "123456:polling-token"
            val upserted = client.put(BackendHttpRoutes.chatTelegramBot(chatId)) {
                trusted(userId)
                jsonBody("""{"token":"$token"}""")
            }
            assertEquals(HttpStatusCode.OK, upserted.status)
            val linkCommand = upserted.jsonBody()["pendingLinkCommand"].asText()
            assertTrue(linkCommand.startsWith("/start "))

            telegramApi.enqueue(update(10, senderId = 701, chatId = -100, chatType = "group", text = linkCommand))
            eventually("group update checkpoint") {
                telegramApi.requestedOffsets.lastOrNull()?.takeIf { it >= 11 }
            }
            assertFalse(binding(userId, chatId)["linked"].asBoolean())
            assertFalse(telegramApi.sentMessages.any { it.chatId == -100L })

            telegramApi.enqueue(update(11, senderId = 701, text = "/start wrong-secret"))
            eventually("invalid private link rejection") {
                telegramApi.sentMessages.firstOrNull {
                    it.chatId == 701L && it.text == "Чтобы привязать этот чат, отправь команду из Souz."
                }
            }
            assertFalse(binding(userId, chatId)["linked"].asBoolean())

            telegramApi.enqueue(update(12, senderId = 701, text = linkCommand, username = "linked_user"))
            val linked = eventually("private Telegram link") {
                binding(userId, chatId).takeIf { it["linked"].asBoolean() }
            }
            assertEquals("linked_user", linked["telegramUsername"].asText())
            assertTrue(telegramApi.sentMessages.any {
                it.chatId == 701L && it.text == "Готово, этот Telegram-аккаунт привязан к чату Souz."
            })

            val discoveryChatId = createPublicChat(userId, "create-discovery")
            val archivedChatId = createPublicChat(userId, "create-archived")
            val foreignChatId = createPublicChat(UUID.randomUUID().toString(), "create-foreign")
            assertEquals(
                HttpStatusCode.OK,
                client.post(BackendHttpRoutes.archiveChat(archivedChatId)) {
                    trusted(userId)
                }.status,
            )
            llm.requestSkillForPrompt("discover channels", "ListActiveChannels", emptyMap())
            val discovery = client.post(BackendHttpRoutes.chatMessages(discoveryChatId)) {
                trusted(userId)
                jsonBody("""{"content":"discover channels","options":{"model":"${E2E_LOCAL_MODEL.alias}"}}""")
            }
            assertEquals(HttpStatusCode.OK, discovery.status)
            val channelResult = eventually("production channel discovery result") {
                llm.requests
                    .filter { request -> request.conversationPrompt() == "discover channels" }
                    .flatMap { request -> request.messages }
                    .lastOrNull { message ->
                        message.role == LLMMessageRole.function && message.name == "RunSkillCommand"
                    }
            }
            val channels = json.readTree(channelResult.content)["channels"]
            assertEquals(
                setOf(
                    "telegram:$chatId",
                    "public_client:$discoveryChatId",
                ),
                channels.map { "${it["channelType"].asText()}:${it["channelId"].asText()}" }.toSet(),
            )
            assertTrue(channels.none { it["channelId"].asText() in setOf(archivedChatId, foreignChatId) })

            telegramApi.enqueue(update(13, senderId = 701, text = "message from Telegram", username = "linked_user"))
            eventually("real-kernel Telegram turn") {
                llm.requests.count { it.conversationPrompt() == "message from Telegram" }.takeIf { it == 1 }
            }
            val messages = eventually("persisted Telegram turn") {
                client.get(BackendHttpRoutes.chatMessages(chatId)) {
                    trusted(userId)
                }.jsonBody()["items"].takeIf { it.size() == 2 }
            }
            assertEquals(listOf("user", "assistant"), messages.map { it["role"].asText() })
            assertEquals("message from Telegram", messages.first()["content"].asText())
            val assistantReply = messages.last()["content"].asText()
            eventually("Telegram assistant reply") {
                telegramApi.sentMessages.firstOrNull { it.chatId == 701L && it.text == assistantReply }
            }
            assertTrue(telegramApi.chatActions.any { it.chatId == 701L && it.action == "typing" })

            eventually("a repeated empty poll after the Telegram checkpoint") {
                telegramApi.requestedOffsets.count { it == 14L }.takeIf { it >= 2 }
            }
            assertEquals(1, llm.requests.count { it.conversationPrompt() == "message from Telegram" })
            assertEquals(
                2,
                client.get(BackendHttpRoutes.chatMessages(chatId)) {
                    trusted(userId)
                }.jsonBody()["items"].size(),
            )

            telegramApi.enqueue(update(14, senderId = 999, text = linkCommand, username = "foreign_user"))
            eventually("foreign Telegram sender rejection") {
                telegramApi.sentMessages.firstOrNull {
                    it.chatId == 999L && it.text == "Этот бот уже привязан к другому Telegram-аккаунту."
                }
            }
            eventually("foreign update checkpoint") {
                telegramApi.requestedOffsets.lastOrNull()?.takeIf { it >= 15 }
            }
            assertEquals(1, llm.requests.count { it.conversationPrompt() == "message from Telegram" })
            assertEquals(
                2,
                client.get(BackendHttpRoutes.chatMessages(chatId)) {
                    trusted(userId)
                }.jsonBody()["items"].size(),
            )
        }
    }

    @Test
    fun `polling discards an update after its database lease is stolen`() {
        val telegramApi = FakeTelegramBotApi()
        val pausedPoll = telegramApi.pauseNextGetUpdates()
        backendE2eTest(
            schemaPrefix = "e2e_telegram_lease_fence",
            featureFlags = BackendFeatureFlags(wsEvents = true, telegramBot = true),
            telegramApi = telegramApi,
            startBackgroundServices = true,
        ) {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId, "create-lease")
            val upserted = client.put(BackendHttpRoutes.chatTelegramBot(chatId)) {
                trusted(userId)
                jsonBody("""{"token":"123456:lease-token"}""")
            }
            assertEquals(HttpStatusCode.OK, upserted.status)
            val linkCommand = upserted.jsonBody()["pendingLinkCommand"].asText()

            eventually("paused Telegram poll holding a binding lease") {
                pausedPoll.takeIf { it.entered.isCompleted }
            }
            pausedPoll.respondWith(update(10, senderId = 701, text = linkCommand))
            sql { connection ->
                connection.prepareStatement(
                    """
                    update telegram_bot_bindings
                    set poller_owner = 'takeover-instance',
                        poller_lease_until = current_timestamp + interval '250 milliseconds'
                    where chat_id = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setObject(1, UUID.fromString(chatId))
                    assertEquals(1, statement.executeUpdate())
                }
            }
            pausedPoll.release.complete(Unit)

            eventually("a later poll after the stolen lease expires") {
                telegramApi.requestedOffsets.size.takeIf { it >= 2 }
            }
            assertFalse(binding(userId, chatId)["linked"].asBoolean())
            assertTrue(telegramApi.sentMessages.isEmpty())
            val checkpoint = sql { connection ->
                connection.prepareStatement(
                    "select last_update_id from telegram_bot_bindings where chat_id = ?"
                ).use { statement ->
                    statement.setObject(1, UUID.fromString(chatId))
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        rows.getLong(1)
                    }
                }
            }
            assertEquals(0L, checkpoint)
        }
    }

    private suspend fun BackendE2eScope.createPublicChat(userId: String, requestId: String): String {
        val created = client.post(BackendHttpRoutes.CHATS) {
            jsonBody("""{"userId":"$userId","requestId":"$requestId","clientType":"backend"}""")
        }
        assertEquals(HttpStatusCode.Created, created.status)
        return created.jsonBody()["chat"]["id"].asText()
    }

    private suspend fun BackendE2eScope.binding(userId: String, chatId: String) =
        assertNotNull(
            client.get(BackendHttpRoutes.chatTelegramBot(chatId)) {
                trusted(userId)
            }.jsonBody()["telegramBot"]
        )

    private fun update(
        id: Long,
        senderId: Long,
        chatId: Long = senderId,
        chatType: String = "private",
        text: String,
        username: String? = null,
    ) = TelegramUpdate(
        updateId = id,
        message = TelegramMessage(
            messageId = id,
            from = TelegramUser(
                id = senderId,
                firstName = "E2E",
                username = username,
            ),
            chat = TelegramChat(id = chatId, type = chatType),
            text = text,
        ),
    )
}

private class FakeTelegramBotApi : TelegramBotApi {
    data class SentMessage(val chatId: Long, val text: String)
    data class ChatAction(val chatId: Long, val action: String)

    val requestedOffsets = CopyOnWriteArrayList<Long>()
    val sentMessages = CopyOnWriteArrayList<SentMessage>()
    val chatActions = CopyOnWriteArrayList<ChatAction>()
    private val updates = CopyOnWriteArrayList<TelegramUpdate>()
    private val nextGetUpdatesPause = AtomicReference<PausedTelegramPoll?>()

    fun enqueue(update: TelegramUpdate) {
        updates += update
    }

    fun pauseNextGetUpdates(): PausedTelegramPoll =
        PausedTelegramPoll().also { pause ->
            check(nextGetUpdatesPause.compareAndSet(null, pause))
        }

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
    ): TelegramUpdatesResponse {
        requestedOffsets += offset ?: 0L
        val pause = nextGetUpdatesPause.getAndSet(null)
        val result = if (pause != null) {
            pause.entered.complete(Unit)
            pause.release.await()
            pause.response()
        } else {
            updates.filter { update -> offset == null || update.updateId >= offset }
        }
        return TelegramUpdatesResponse(
            ok = true,
            result = result,
        )
    }

    override suspend fun sendMessage(token: String, chatId: Long, text: String) {
        sentMessages += SentMessage(chatId, text)
    }

    override suspend fun sendChatAction(token: String, chatId: Long, action: String) {
        chatActions += ChatAction(chatId, action)
    }

    override suspend fun deleteWebhook(token: String, dropPendingUpdates: Boolean) = Unit
}

private class PausedTelegramPoll {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    private val response = AtomicReference<List<TelegramUpdate>>(emptyList())

    fun respondWith(update: TelegramUpdate) {
        response.set(listOf(update))
    }

    fun response(): List<TelegramUpdate> = response.get()
}
