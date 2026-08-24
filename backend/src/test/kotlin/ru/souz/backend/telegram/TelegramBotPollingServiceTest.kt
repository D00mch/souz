package ru.souz.backend.telegram

import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.chat.model.ChatMessage
import ru.souz.backend.chat.model.ChatRole
import ru.souz.backend.chat.service.SendMessageResult
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.settings.service.UserSettingsOverrides
import ru.souz.backend.storage.postgres.PostgresChatRepository
import ru.souz.backend.storage.postgres.PostgresDataSourceFactory
import ru.souz.backend.storage.postgres.PostgresTelegramBotBindingRepository
import ru.souz.backend.storage.postgres.PostgresUserRepository
import ru.souz.backend.storage.postgres.newPostgresSchema
import ru.souz.backend.storage.postgres.postgresAppConfig
import kotlin.time.Duration.Companion.milliseconds

class TelegramBotPollingServiceTest {
    @Test
    fun `long Telegram turn renews lease and lost lease fences reply and checkpoint`() = runBlocking {
        val schema = newPostgresSchema("telegram_polling_renewal")
        val dataSource = PostgresDataSourceFactory.create(
            postgresAppConfig(schema, telegramTokenEncryptionKey = TEST_TELEGRAM_KEY).postgres
        )
        dataSource.use { source ->
            val userId = UUID.randomUUID().toString()
            val chatId = UUID.randomUUID()
            val tokenCrypto = TelegramBotTokenCrypto(TEST_TELEGRAM_KEY)
            val bindingRepository = PostgresTelegramBotBindingRepository(source)
            PostgresUserRepository(source).ensureUser(userId)
            PostgresChatRepository(source).create(
                Chat(
                    id = chatId,
                    userId = userId,
                    title = "Telegram lease",
                    archived = false,
                    createdAt = Instant.parse("2026-08-24T00:00:00Z"),
                    updatedAt = Instant.parse("2026-08-24T00:00:00Z"),
                )
            )
            val binding = bindingRepository.upsertForChat(
                userId = userId,
                chatId = chatId,
                botToken = tokenCrypto.encrypt("123456:renewal-token"),
                botTokenHash = sha256("123456:renewal-token"),
                linkSecretHash = sha256("link-secret"),
                botUsername = "souze2ebot",
                botFirstName = "Souz",
                now = Instant.parse("2026-08-24T00:00:01Z"),
            )
            val linked = bindingRepository.claimTelegramUser(
                id = binding.id,
                linkSecretHash = sha256("link-secret"),
                telegramUserId = 701L,
                telegramChatId = 701L,
                telegramUsername = "linked_user",
                telegramFirstName = "Linked",
                telegramLastName = null,
                linkedAt = Instant.parse("2026-08-24T00:00:02Z"),
            )
            assertTrue(linked is TelegramUserClaimResult.Claimed)

            val turnStarted = CompletableDeferred<Unit>()
            val releaseTurn = CompletableDeferred<Unit>()
            val api = LeaseTelegramApi(update(id = 30, senderId = 701L, text = "long lease turn"))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val service = TelegramBotPollingService(
                repository = bindingRepository,
                botApi = api,
                turnExecutor = { id, targetChatId, content, _, _: UserSettingsOverrides ->
                    assertEquals(userId, id)
                    assertEquals(chatId, targetChatId)
                    assertEquals("long lease turn", content)
                    turnStarted.complete(Unit)
                    releaseTurn.await()
                    completedTurn(userId, chatId, content)
                },
                tokenCrypto = tokenCrypto,
                scope = scope,
                instanceId = "renewal-test-instance",
                pollLoopDelayMs = 10L,
                leaseTtlSeconds = 3L,
                maxConcurrency = 1,
            )

            try {
                val poll = scope.async { service.pollEnabledOnce() }
                withTimeout(5.seconds) { turnStarted.await() }
                val initialLease = assertNotNull(bindingRepository.getByChat(chatId)?.pollerLeaseUntil)
                var renewedLease: Instant? = null
                withTimeout(5.seconds) {
                    while (renewedLease == null) {
                        val candidate = bindingRepository.getByChat(chatId)?.pollerLeaseUntil
                        if (candidate != null && candidate.isAfter(initialLease)) {
                            renewedLease = candidate
                        }
                        delay(25.milliseconds)
                    }
                }
                assertTrue(assertNotNull(renewedLease).isAfter(initialLease))

                source.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        update telegram_bot_bindings
                        set poller_owner = 'stolen-instance',
                            poller_lease_until = current_timestamp + interval '1 minute'
                        where id = ?
                        """.trimIndent()
                    ).use { statement ->
                        statement.setObject(1, binding.id)
                        assertEquals(1, statement.executeUpdate())
                    }
                }
                releaseTurn.complete(Unit)
                withTimeout(5.seconds) { poll.join() }

                assertTrue(api.sentMessages.isEmpty())
                assertEquals(0L, assertNotNull(bindingRepository.getByChat(chatId)).lastUpdateId)
            } finally {
                scope.cancel()
            }
        }
    }

    private fun completedTurn(userId: String, chatId: UUID, content: String): SendMessageResult =
        SendMessageResult(
            userMessage = ChatMessage(
                id = UUID.randomUUID(),
                userId = userId,
                chatId = chatId,
                seq = 1,
                role = ChatRole.USER,
                content = content,
                metadata = emptyMap(),
                createdAt = Instant.parse("2026-08-24T00:00:03Z"),
            ),
            assistantMessage = null,
            execution = AgentExecution(
                id = UUID.randomUUID(),
                userId = userId,
                chatId = chatId,
                userMessageId = null,
                assistantMessageId = null,
                status = AgentExecutionStatus.COMPLETED,
                requestId = null,
                clientMessageId = null,
                model = null,
                provider = null,
                startedAt = Instant.parse("2026-08-24T00:00:03Z"),
                finishedAt = Instant.parse("2026-08-24T00:00:04Z"),
                cancelRequested = false,
                errorCode = null,
                errorMessage = null,
                usage = null,
                metadata = emptyMap(),
            ),
        )
}

private class LeaseTelegramApi(private val update: TelegramUpdate) : TelegramBotApi {
    val sentMessages = CopyOnWriteArrayList<String>()

    override suspend fun getMe(token: String): TelegramGetMeResponse =
        TelegramGetMeResponse(ok = true)

    override suspend fun getUpdates(
        token: String,
        offset: Long?,
        timeoutSeconds: Int,
        allowedUpdates: List<String>,
    ): TelegramUpdatesResponse =
        TelegramUpdatesResponse(ok = true, result = listOf(update))

    override suspend fun sendMessage(token: String, chatId: Long, text: String) {
        sentMessages += text
    }

    override suspend fun sendChatAction(token: String, chatId: Long, action: String) = Unit

    override suspend fun deleteWebhook(token: String, dropPendingUpdates: Boolean) = Unit
}

private fun update(id: Long, senderId: Long, text: String): TelegramUpdate =
    TelegramUpdate(
        updateId = id,
        message = TelegramMessage(
            messageId = id,
            from = TelegramUser(id = senderId, firstName = "E2E", username = "linked_user"),
            chat = TelegramChat(id = senderId, type = "private"),
            text = text,
        ),
    )

private const val TEST_TELEGRAM_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
