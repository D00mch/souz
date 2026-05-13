package ru.souz.backend.telegram

import io.ktor.http.HttpStatusCode
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ru.souz.backend.chat.model.ChatMessage
import ru.souz.backend.chat.model.ChatRole
import ru.souz.backend.chat.service.SendMessageResult
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.http.BackendV1Exception
import ru.souz.backend.settings.service.UserSettingsOverrides
import ru.souz.backend.storage.memory.MemoryTelegramBotBindingRepository

class TelegramBotPollingServiceTest {
    @Test
    fun `matching start command links account and does not execute agent`() = runTest {
        val repository = MemoryTelegramBotBindingRepository()
        val botApi = FakePollingTelegramBotApi()
        val executor = RecordingTelegramTurnExecutor()
        val service = pollingService(repository, botApi, executor)
        val linkSecret = "link-secret-123"
        val binding = pendingBinding(repository, rawToken = "123456:link-token", linkSecret = linkSecret)
        botApi.enqueueUpdates(
            token = "123456:link-token",
            updater = {
                TelegramUpdatesResponse(
                    ok = true,
                    result = listOf(
                        privateTextUpdate(
                            updateId = 15L,
                            chatId = 777L,
                            userId = 555L,
                            text = "/start $linkSecret",
                            username = "alice",
                            firstName = "Alice",
                            lastName = "Doe",
                        )
                    ),
                )
            },
        )

        service.pollEnabledOnce()

        val stored = repository.getByChat(binding.chatId)
        assertTrue(executor.calls.isEmpty())
        assertNotNull(stored)
        assertEquals(555L, stored.telegramUserId)
        assertEquals(777L, stored.telegramChatId)
        assertEquals("alice", stored.telegramUsername)
        assertEquals("Alice", stored.telegramFirstName)
        assertEquals("Doe", stored.telegramLastName)
        assertNotNull(stored.linkedAt)
        assertEquals(null, stored.linkSecretHash)
        assertEquals(15L, stored.lastUpdateId)
        assertEquals(
            listOf(SentTelegramMessage(777L, "Готово, этот Telegram-аккаунт привязан к чату Souz.")),
            botApi.sentMessages,
        )
    }

    @Test
    fun `pending binding ignores stale text until valid start secret arrives`() = runTest {
        val repository = MemoryTelegramBotBindingRepository()
        val botApi = FakePollingTelegramBotApi()
        val executor = RecordingTelegramTurnExecutor(assistantResponse = "ok")
        val service = pollingService(repository, botApi, executor)
        val linkSecret = "link-secret-456"
        val binding = pendingBinding(repository, rawToken = "123456:pending-token", linkSecret = linkSecret)
        botApi.enqueueUpdates(
            token = "123456:pending-token",
            updater = {
                TelegramUpdatesResponse(
                    ok = true,
                    result = listOf(
                        privateTextUpdate(
                            updateId = 11L,
                            chatId = 777L,
                            userId = 555L,
                            text = "old pending text",
                            username = "alice",
                            firstName = "Alice",
                        ),
                        privateTextUpdate(
                            updateId = 12L,
                            chatId = 777L,
                            userId = 555L,
                            text = "/start wrong-secret",
                            username = "alice",
                            firstName = "Alice",
                        ),
                        privateTextUpdate(
                            updateId = 13L,
                            chatId = 777L,
                            userId = 555L,
                            text = "/start $linkSecret",
                            username = "alice",
                            firstName = "Alice",
                        ),
                        privateTextUpdate(
                            updateId = 14L,
                            chatId = 777L,
                            userId = 555L,
                            text = "do work",
                            username = "alice",
                            firstName = "Alice",
                        ),
                    ),
                )
            },
        )

        service.pollEnabledOnce()

        assertEquals(
            listOf(
                TelegramTurnCall(
                    userId = "user-a",
                    chatId = binding.chatId,
                    content = "do work",
                    clientMessageId = "telegram:${binding.id}:14",
                    requestOverrides = UserSettingsOverrides(streamingMessages = false),
                    observedLastUpdateId = 13L,
                )
            ),
            executor.calls,
        )
        assertEquals(
            listOf(
                SentTelegramMessage(777L, "Чтобы привязать этот чат, отправь команду из Souz."),
                SentTelegramMessage(777L, "Готово, этот Telegram-аккаунт привязан к чату Souz."),
                SentTelegramMessage(777L, "ok"),
            ),
            botApi.sentMessages,
        )
        assertEquals(14L, repository.getByChat(binding.chatId)?.lastUpdateId)
    }

    @Test
    fun `linked private telegram message executes turn with scoped client id and sends assistant reply`() = runTest {
        val repository = MemoryTelegramBotBindingRepository()
        val botApi = FakePollingTelegramBotApi()
        val executor = RecordingTelegramTurnExecutor(assistantResponse = "Финальный ответ агента.")
        val service = pollingService(repository, botApi, executor)
        val binding = linkedBinding(repository)
        botApi.enqueueUpdates(
            token = "123456:linked-token",
            updater = { offset ->
                assertEquals(1L, offset)
                TelegramUpdatesResponse(
                    ok = true,
                    result = listOf(
                        privateTextUpdate(
                            updateId = 15L,
                            chatId = 777L,
                            userId = 555L,
                            text = "  hello from telegram  ",
                            username = "alice",
                            firstName = "Alice",
                        )
                    ),
                )
            },
        )

        service.pollEnabledOnce()

        assertEquals(
            listOf(
                TelegramTurnCall(
                    userId = "user-a",
                    chatId = binding.chatId,
                    content = "hello from telegram",
                    clientMessageId = "telegram:${binding.id}:15",
                    requestOverrides = UserSettingsOverrides(streamingMessages = false),
                    observedLastUpdateId = 0L,
                )
            ),
            executor.calls,
        )
        assertEquals(
            listOf(SentTelegramMessage(777L, "Финальный ответ агента.")),
            botApi.sentMessages,
        )
        assertEquals(
            listOf(GetUpdatesCall("123456:linked-token", 1L, 30, listOf("message"))),
            botApi.getUpdatesCalls,
        )
    }

    @Test
    fun `null or blank assistant response sends fallback done reply`() = runTest {
        val repository = MemoryTelegramBotBindingRepository()
        val botApi = FakePollingTelegramBotApi()
        val executor = RecordingTelegramTurnExecutor(assistantResponse = "   ")
        val service = pollingService(repository, botApi, executor)
        linkedBinding(repository)
        botApi.enqueueSingleTextUpdate(
            token = "123456:linked-token",
            updateId = 18L,
            chatId = 777L,
            userId = 555L,
            text = "do work",
        )

        service.pollEnabledOnce()

        assertEquals(listOf(SentTelegramMessage(777L, "Готово.")), botApi.sentMessages)
    }

    @Test
    fun `long assistant response is split into telegram sized chunks`() = runTest {
        val repository = MemoryTelegramBotBindingRepository()
        val botApi = FakePollingTelegramBotApi()
        val assistantResponse = buildString {
            repeat(1200) {
                append("line ")
                append(it)
                append('\n')
            }
        }
        val executor = RecordingTelegramTurnExecutor(assistantResponse = assistantResponse)
        val service = pollingService(repository, botApi, executor)
        linkedBinding(repository)
        botApi.enqueueSingleTextUpdate(
            token = "123456:linked-token",
            updateId = 19L,
            chatId = 777L,
            userId = 555L,
            text = "long reply please",
        )

        service.pollEnabledOnce()

        assertTrue(botApi.sentMessages.size > 1)
        assertTrue(botApi.sentMessages.all { it.text.length <= 4096 })
        assertEquals(assistantResponse, botApi.sentMessages.joinToString(separator = "") { it.text })
    }

    @Test
    fun `blank telegram text still advances update id and skips execution`() = runTest {
        val repository = MemoryTelegramBotBindingRepository()
        val botApi = FakePollingTelegramBotApi()
        val executor = RecordingTelegramTurnExecutor()
        val service = pollingService(repository, botApi, executor)
        val binding = linkedBinding(repository)
        botApi.enqueueUpdates(
            token = "123456:linked-token",
            updater = {
                TelegramUpdatesResponse(
                    ok = true,
                    result = listOf(
                        privateTextUpdate(
                            updateId = 22L,
                            chatId = 777L,
                            userId = 555L,
                            text = "   ",
                            username = "alice",
                            firstName = "Alice",
                        )
                    ),
                )
            },
        )

        service.pollEnabledOnce()

        assertTrue(executor.calls.isEmpty())
        assertEquals(22L, repository.getByChat(binding.chatId)?.lastUpdateId)
        assertTrue(botApi.sentMessages.isEmpty())
    }

    @Test
    fun `message from another telegram account after linking is rejected and still advances update id`() = runTest {
        val repository = MemoryTelegramBotBindingRepository()
        val botApi = FakePollingTelegramBotApi()
        val executor = RecordingTelegramTurnExecutor()
        val service = pollingService(repository, botApi, executor)
        val binding = linkedBinding(repository)
        botApi.enqueueSingleTextUpdate(
            token = "123456:linked-token",
            updateId = 23L,
            chatId = 999L,
            userId = 999L,
            text = "steal chat",
        )

        service.pollEnabledOnce()

        assertTrue(executor.calls.isEmpty())
        assertEquals(23L, repository.getByChat(binding.chatId)?.lastUpdateId)
        assertEquals(
            listOf(SentTelegramMessage(999L, "Этот бот уже привязан к другому Telegram-аккаунту.")),
            botApi.sentMessages,
        )
    }

    @Test
    fun `group message does not link binding and does not execute agent`() = runTest {
        val repository = MemoryTelegramBotBindingRepository()
        val botApi = FakePollingTelegramBotApi()
        val executor = RecordingTelegramTurnExecutor()
        val service = pollingService(repository, botApi, executor)
        val binding = repository.upsertForChat(
            userId = "user-a",
            chatId = UUID.randomUUID(),
            botToken = "123456:group-token",
            botTokenHash = sha256("123456:group-token"),
            linkSecretHash = sha256("group-link-secret"),
            botUsername = "souz_bot",
            botFirstName = "Souz",
            now = Instant.parse("2026-05-04T10:00:00Z"),
        )
        botApi.enqueueUpdates(
            token = "123456:group-token",
            updater = {
                TelegramUpdatesResponse(
                    ok = true,
                    result = listOf(
                        TelegramUpdate(
                            updateId = 24L,
                            message = TelegramMessage(
                                messageId = 24L,
                                from = TelegramUser(
                                    id = 444L,
                                    isBot = false,
                                    firstName = "Group",
                                    username = "group_user",
                                ),
                                chat = TelegramChat(id = -2000L, type = "group"),
                                text = "hello group",
                            ),
                        )
                    ),
                )
            },
        )

        service.pollEnabledOnce()

        val stored = repository.getByChat(binding.chatId)
        assertTrue(executor.calls.isEmpty())
        assertNotNull(stored)
        assertEquals(null, stored.telegramUserId)
        assertEquals(null, stored.telegramChatId)
        assertEquals(24L, stored.lastUpdateId)
        assertTrue(botApi.sentMessages.isEmpty())
    }

    @Test
    fun `too long telegram text is rejected without starting agent`() = runTest {
        val repository = MemoryTelegramBotBindingRepository()
        val botApi = FakePollingTelegramBotApi()
        val executor = RecordingTelegramTurnExecutor()
        val service = pollingService(repository, botApi, executor)
        linkedBinding(repository)
        botApi.enqueueSingleTextUpdate(
            token = "123456:linked-token",
            updateId = 25L,
            chatId = 777L,
            userId = 555L,
            text = "x".repeat(8_001),
        )

        service.pollEnabledOnce()

        assertTrue(executor.calls.isEmpty())
        assertEquals(listOf(SentTelegramMessage(777L, "Сообщение слишком длинное.")), botApi.sentMessages)
    }

    @Test
    fun `repeated polling does not duplicate update after last update id advances`() = runTest {
        val repository = MemoryTelegramBotBindingRepository()
        val botApi = FakePollingTelegramBotApi()
        val executor = RecordingTelegramTurnExecutor()
        val service = pollingService(repository, botApi, executor)
        val binding = linkedBinding(repository)
        botApi.enqueueUpdates(
            token = "123456:linked-token",
            updater = { offset ->
                if (offset == null || offset <= 15L) {
                    TelegramUpdatesResponse(
                        ok = true,
                        result = listOf(
                            privateTextUpdate(
                                updateId = 15L,
                                chatId = 777L,
                                userId = 555L,
                                text = "repeat once",
                                username = "alice",
                                firstName = "Alice",
                            )
                        ),
                    )
                } else {
                    TelegramUpdatesResponse(ok = true, result = emptyList())
                }
            },
        )

        service.pollEnabledOnce()
        service.pollEnabledOnce()

        assertEquals(1, executor.calls.size)
        assertEquals(15L, repository.getByChat(binding.chatId)?.lastUpdateId)
    }

    @Test
    fun `active execution conflict sends conflict reply`() = runTest {
        val repository = MemoryTelegramBotBindingRepository()
        val botApi = FakePollingTelegramBotApi()
        val executor = RecordingTelegramTurnExecutor(
            failure = BackendV1Exception(
                status = HttpStatusCode.Conflict,
                code = "chat_already_has_active_execution",
                message = "busy",
            )
        )
        val service = pollingService(repository, botApi, executor)
        linkedBinding(repository)
        botApi.enqueueSingleTextUpdate(
            token = "123456:linked-token",
            updateId = 29L,
            chatId = 777L,
            userId = 555L,
            text = "do work",
        )

        service.pollEnabledOnce()

        assertEquals(
            listOf(SentTelegramMessage(777L, "В этом чате уже выполняется задача. Попробуй позже.")),
            botApi.sentMessages,
        )
    }

    @Test
    fun `execution failure sends generic reply`() = runTest {
        val repository = MemoryTelegramBotBindingRepository()
        val botApi = FakePollingTelegramBotApi()
        val executor = RecordingTelegramTurnExecutor(failure = IllegalStateException("boom"))
        val service = pollingService(repository, botApi, executor)
        linkedBinding(repository)
        botApi.enqueueSingleTextUpdate(
            token = "123456:linked-token",
            updateId = 30L,
            chatId = 777L,
            userId = 555L,
            text = "do work",
        )

        service.pollEnabledOnce()

        assertEquals(
            listOf(SentTelegramMessage(777L, "Не удалось выполнить команду.")),
            botApi.sentMessages,
        )
    }

    @Test
    fun `in flight dedupe does not send fallback done reply`() = runTest {
        val repository = MemoryTelegramBotBindingRepository()
        val botApi = FakePollingTelegramBotApi()
        val executor = RecordingTelegramTurnExecutor(
            assistantResponse = null,
            responseExecutionStatus = AgentExecutionStatus.RUNNING,
        )
        val service = pollingService(repository, botApi, executor)
        linkedBinding(repository)
        botApi.enqueueSingleTextUpdate(
            token = "123456:linked-token",
            updateId = 30L,
            chatId = 777L,
            userId = 555L,
            text = "do work",
        )

        service.pollEnabledOnce()

        assertEquals(
            listOf(SentTelegramMessage(777L, "В этом чате уже выполняется задача. Попробуй позже.")),
            botApi.sentMessages,
        )
    }

    @Test
    fun `telegram unauthorized disables binding and stores stable error`() = runTest {
        val repository = MemoryTelegramBotBindingRepository()
        val botApi = FakePollingTelegramBotApi()
        val executor = RecordingTelegramTurnExecutor()
        val service = pollingService(repository, botApi, executor)
        val binding = linkedBinding(repository)
        botApi.enqueueUpdates(
            token = "123456:linked-token",
            updater = {
                TelegramUpdatesResponse(
                    ok = false,
                    description = "Unauthorized",
                    errorCode = 401,
                    parameters = TelegramResponseParameters(),
                )
            },
        )

        service.pollEnabledOnce()

        val stored = repository.getByChat(binding.chatId)
        assertNotNull(stored)
        assertEquals(false, stored.enabled)
        assertEquals("telegram_unauthorized", stored.lastError)
        assertNotNull(stored.lastErrorAt)
        assertTrue(executor.calls.isEmpty())
    }

    @Test
    fun `one binding failure does not stop another binding`() = runTest {
        val repository = MemoryTelegramBotBindingRepository()
        val botApi = FakePollingTelegramBotApi()
        val executor = RecordingTelegramTurnExecutor(assistantResponse = "ok")
        val service = pollingService(repository, botApi, executor)
        val failingBinding = repository.upsertForChat(
            userId = "user-a",
            chatId = UUID.randomUUID(),
            botToken = "123456:failing-binding",
            botTokenHash = sha256("123456:failing-binding"),
            linkSecretHash = sha256("failing-link-secret"),
            botUsername = "failing_bot",
            botFirstName = "Failing",
            now = Instant.parse("2026-05-04T10:00:00Z"),
        )
        val healthyBinding = linkedBinding(repository, rawToken = "123456:healthy-binding", userId = "user-b")
        botApi.enqueueFailure(
            token = "123456:failing-binding",
            error = IOException("network"),
        )
        botApi.enqueueSingleTextUpdate(
            token = "123456:healthy-binding",
            updateId = 31L,
            chatId = 777L,
            userId = 555L,
            text = "healthy update",
        )

        service.pollEnabledOnce()

        assertEquals(1, executor.calls.size)
        assertEquals(healthyBinding.chatId, executor.calls.single().chatId)
        assertEquals("telegram_network_error", repository.getByChat(failingBinding.chatId)?.lastError)
        assertEquals(
            listOf(SentTelegramMessage(777L, "ok")),
            botApi.sentMessages,
        )
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `polling loop survives listEnabled exception`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher + SupervisorJob())
        val repository = FlakyListEnabledTelegramBindingRepository()
        val botApi = FakePollingTelegramBotApi()
        val executor = RecordingTelegramTurnExecutor()
        val service = TelegramBotPollingService(
            repository = repository,
            botApi = botApi,
            turnExecutor = executor,
            tokenCrypto = TelegramBotTokenCrypto(TEST_TELEGRAM_TOKEN_ENCRYPTION_KEY),
            scope = scope,
            pollLoopDelayMs = 50L,
        )

        service.start()
        scope.advanceTimeBy(160L)

        assertTrue(repository.listEnabledCalls >= 2)
        scope.cancel()
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `lease is renewed while long turn is executing`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val clock = schedulerClock(this)
        val repository = MemoryTelegramBotBindingRepository()
        val botApi = FakePollingTelegramBotApi()
        val firstExecutor = RecordingTelegramTurnExecutor(
            assistantResponse = "done",
            delayMs = 8_000L,
        )
        val secondExecutor = RecordingTelegramTurnExecutor(assistantResponse = "duplicate")
        firstExecutor.repository = repository
        secondExecutor.repository = repository
        val binding = linkedBinding(repository)
        val firstService = TelegramBotPollingService(
            repository = repository,
            botApi = botApi,
            turnExecutor = firstExecutor,
            tokenCrypto = TelegramBotTokenCrypto(TEST_TELEGRAM_TOKEN_ENCRYPTION_KEY),
            scope = backgroundScope,
            clock = clock,
            instanceId = "instance-a",
            leaseTtlSeconds = 6L,
        )
        val secondService = TelegramBotPollingService(
            repository = repository,
            botApi = botApi,
            turnExecutor = secondExecutor,
            tokenCrypto = TelegramBotTokenCrypto(TEST_TELEGRAM_TOKEN_ENCRYPTION_KEY),
            scope = backgroundScope,
            clock = clock,
            instanceId = "instance-b",
            leaseTtlSeconds = 6L,
        )
        botApi.enqueueUpdates(
            token = "123456:linked-token",
            updater = { offset ->
                assertEquals(1L, offset)
                TelegramUpdatesResponse(
                    ok = true,
                    result = listOf(
                        privateTextUpdate(
                            updateId = 50L,
                            chatId = 777L,
                            userId = 555L,
                            text = "long work",
                            username = "alice",
                            firstName = "Alice",
                        )
                    ),
                )
            },
        )

        val firstPoll = backgroundScope.launch(dispatcher) {
            firstService.pollEnabledOnce()
        }
        runCurrent()
        advanceTimeBy(6_500L)

        secondService.pollEnabledOnce()

        assertTrue(secondExecutor.calls.isEmpty())
        assertEquals(1, botApi.getUpdatesCalls.size)

        advanceTimeBy(2_000L)
        firstPoll.join()

        assertEquals(1, firstExecutor.calls.size)
        assertEquals(50L, repository.getByChat(binding.chatId)?.lastUpdateId)
        assertEquals(listOf(SentTelegramMessage(777L, "done")), botApi.sentMessages)
    }

    @Test
    fun `assistant reply is skipped when lease is lost after execution starts`() = runTest {
        val repository = LeaseLossTelegramBindingRepository(MemoryTelegramBotBindingRepository())
        val botApi = FakePollingTelegramBotApi()
        val executor = RecordingTelegramTurnExecutor(
            assistantResponse = "should not send",
            onExecute = {
                repository.leaseValid = false
            },
        )
        val service = pollingService(repository, botApi, executor)
        val binding = linkedBinding(repository)
        botApi.enqueueSingleTextUpdate(
            token = "123456:linked-token",
            updateId = 60L,
            chatId = 777L,
            userId = 555L,
            text = "do work",
        )

        service.pollEnabledOnce()

        assertEquals(1, executor.calls.size)
        assertTrue(botApi.sentMessages.isEmpty())
        assertEquals(0L, repository.getByChat(binding.chatId)?.lastUpdateId)
    }
}

private fun pollingService(
    repository: TelegramBotBindingRepository,
    botApi: FakePollingTelegramBotApi,
    executor: RecordingTelegramTurnExecutor,
): TelegramBotPollingService {
    executor.repository = repository
    return TelegramBotPollingService(
        repository = repository,
        botApi = botApi,
        turnExecutor = executor,
        tokenCrypto = TelegramBotTokenCrypto(TEST_TELEGRAM_TOKEN_ENCRYPTION_KEY),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )
}

private suspend fun pendingBinding(
    repository: TelegramBotBindingRepository,
    rawToken: String = "123456:pending-token",
    linkSecret: String = "link-secret",
    userId: String = "user-a",
): TelegramBotBinding =
    repository.upsertForChat(
        userId = userId,
        chatId = UUID.randomUUID(),
        botToken = rawToken,
        botTokenHash = sha256(rawToken),
        linkSecretHash = sha256(linkSecret),
        botUsername = "souz_bot",
        botFirstName = "Souz",
        now = Instant.parse("2026-05-04T10:00:00Z"),
    )

private suspend fun linkedBinding(
    repository: TelegramBotBindingRepository,
    rawToken: String = "123456:linked-token",
    userId: String = "user-a",
): TelegramBotBinding {
    val linkSecret = "linked-secret"
    val binding = pendingBinding(
        repository = repository,
        rawToken = rawToken,
        linkSecret = linkSecret,
        userId = userId,
    )
    return when (
        val result = repository.claimTelegramUser(
            id = binding.id,
            linkSecretHash = sha256(linkSecret),
            telegramUserId = 555L,
            telegramChatId = 777L,
            telegramUsername = "alice",
            telegramFirstName = "Alice",
            telegramLastName = null,
            linkedAt = Instant.parse("2026-05-04T10:01:00Z"),
        )
    ) {
        is TelegramUserClaimResult.Claimed -> result.binding
        else -> error("Expected claimed binding, got $result")
    }
}

private fun privateTextUpdate(
    updateId: Long,
    chatId: Long,
    userId: Long,
    text: String,
    username: String?,
    firstName: String?,
    lastName: String? = null,
): TelegramUpdate =
    TelegramUpdate(
        updateId = updateId,
        message = TelegramMessage(
            messageId = updateId,
            from = TelegramUser(
                id = userId,
                isBot = false,
                firstName = firstName,
                lastName = lastName,
                username = username,
            ),
            chat = TelegramChat(id = chatId, type = "private"),
            text = text,
        ),
    )

private data class TelegramTurnCall(
    val userId: String,
    val chatId: UUID,
    val content: String,
    val clientMessageId: String,
    val requestOverrides: UserSettingsOverrides,
    val observedLastUpdateId: Long?,
)

private data class SentTelegramMessage(
    val chatId: Long,
    val text: String,
)

private data class GetUpdatesCall(
    val token: String,
    val offset: Long?,
    val timeoutSeconds: Int,
    val allowedUpdates: List<String>,
)

private class RecordingTelegramTurnExecutor(
    private val assistantResponse: String? = "Готово",
    private val failure: Throwable? = null,
    private val responseExecutionStatus: AgentExecutionStatus = AgentExecutionStatus.COMPLETED,
    private val delayMs: Long = 0L,
    private val onExecute: suspend () -> Unit = {},
) : TelegramTurnExecutor {
    val calls = mutableListOf<TelegramTurnCall>()
    lateinit var repository: TelegramBotBindingRepository

    override suspend fun execute(
        userId: String,
        chatId: UUID,
        content: String,
        clientMessageId: String,
        requestOverrides: UserSettingsOverrides,
    ): SendMessageResult {
        calls += TelegramTurnCall(
            userId = userId,
            chatId = chatId,
            content = content,
            clientMessageId = clientMessageId,
            requestOverrides = requestOverrides,
            observedLastUpdateId = repository.getByChat(chatId)?.lastUpdateId,
        )
        onExecute()
        if (delayMs > 0L) {
            delay(delayMs)
        }
        failure?.let { throw it }
        return sendMessageResult(
            chatId = chatId,
            userId = userId,
            content = content,
            clientMessageId = clientMessageId,
            assistantResponse = assistantResponse,
            executionStatus = responseExecutionStatus,
        )
    }
}

private fun sendMessageResult(
    chatId: UUID,
    userId: String,
    content: String,
    clientMessageId: String,
    assistantResponse: String?,
    executionStatus: AgentExecutionStatus = AgentExecutionStatus.COMPLETED,
): SendMessageResult {
    val userMessage = ChatMessage(
        id = UUID.randomUUID(),
        userId = userId,
        chatId = chatId,
        seq = 1L,
        role = ChatRole.USER,
        content = content,
        metadata = mapOf("clientMessageId" to clientMessageId),
        createdAt = Instant.parse("2026-05-04T10:02:00Z"),
    )
    val assistantMessage = assistantResponse?.let { response ->
        ChatMessage(
            id = UUID.randomUUID(),
            userId = userId,
            chatId = chatId,
            seq = 2L,
            role = ChatRole.ASSISTANT,
            content = response,
            metadata = emptyMap(),
            createdAt = Instant.parse("2026-05-04T10:02:01Z"),
        )
    }
    return SendMessageResult(
        userMessage = userMessage,
        assistantMessage = assistantMessage,
        execution = AgentExecution(
            id = UUID.randomUUID(),
            userId = userId,
            chatId = chatId,
            userMessageId = userMessage.id,
            assistantMessageId = assistantMessage?.id,
            status = executionStatus,
            requestId = null,
            clientMessageId = clientMessageId,
            model = null,
            provider = null,
            startedAt = Instant.parse("2026-05-04T10:02:00Z"),
            finishedAt = Instant.parse("2026-05-04T10:02:05Z")
                .takeIf { executionStatus == AgentExecutionStatus.COMPLETED },
            cancelRequested = false,
            errorCode = null,
            errorMessage = null,
            usage = null,
            metadata = emptyMap(),
        ),
    )
}

private class FakePollingTelegramBotApi : TelegramBotApi {
    val sentMessages = mutableListOf<SentTelegramMessage>()
    val getUpdatesCalls = mutableListOf<GetUpdatesCall>()

    private val updateHandlers = LinkedHashMap<String, suspend (Long?) -> TelegramUpdatesResponse>()
    private val updateFailures = LinkedHashMap<String, Throwable>()

    fun enqueueUpdates(
        token: String,
        updater: suspend (Long?) -> TelegramUpdatesResponse,
    ) {
        updateHandlers[token] = updater
    }

    fun enqueueSingleTextUpdate(
        token: String,
        updateId: Long,
        chatId: Long,
        userId: Long,
        text: String,
    ) {
        enqueueUpdates(token) {
            TelegramUpdatesResponse(
                ok = true,
                result = listOf(
                    privateTextUpdate(
                        updateId = updateId,
                        chatId = chatId,
                        userId = userId,
                        text = text,
                        username = "alice",
                        firstName = "Alice",
                    )
                ),
            )
        }
    }

    fun enqueueFailure(
        token: String,
        error: Throwable,
    ) {
        updateFailures[token] = error
    }

    override suspend fun getMe(token: String): TelegramGetMeResponse =
        TelegramGetMeResponse(
            ok = true,
            result = TelegramUser(
                id = 42L,
                isBot = true,
                firstName = "Souz",
                username = "souz_bot",
            ),
        )

    override suspend fun getUpdates(
        token: String,
        offset: Long?,
        timeoutSeconds: Int,
        allowedUpdates: List<String>,
    ): TelegramUpdatesResponse {
        getUpdatesCalls += GetUpdatesCall(token, offset, timeoutSeconds, allowedUpdates)
        updateFailures[token]?.let { throw it }
        return updateHandlers[token]?.invoke(offset) ?: TelegramUpdatesResponse(ok = true, result = emptyList())
    }

    override suspend fun deleteWebhook(
        token: String,
        dropPendingUpdates: Boolean,
    ) = Unit

    override suspend fun sendMessage(
        token: String,
        chatId: Long,
        text: String,
    ) {
        sentMessages += SentTelegramMessage(chatId, text)
    }
}

private class FlakyListEnabledTelegramBindingRepository : TelegramBotBindingRepository {
    var listEnabledCalls: Int = 0

    override suspend fun getByChat(chatId: UUID): TelegramBotBinding? = null

    override suspend fun getByUserAndChat(
        userId: String,
        chatId: UUID,
    ): TelegramBotBinding? = null

    override suspend fun findByTokenHash(botTokenHash: String): TelegramBotBinding? = null

    override suspend fun listEnabled(): List<TelegramBotBinding> {
        listEnabledCalls += 1
        if (listEnabledCalls == 1) {
            throw IllegalStateException("boom")
        }
        return emptyList()
    }

    override suspend fun upsertForChat(
        userId: String,
        chatId: UUID,
        botToken: String,
        botTokenHash: String,
        linkSecretHash: String,
        botUsername: String?,
        botFirstName: String?,
        now: Instant,
    ): TelegramBotBinding = error("Not used in this test")

    override suspend fun deleteByChat(chatId: UUID) = Unit

    override suspend fun updateLastUpdateId(
        id: UUID,
        lastUpdateId: Long,
        updatedAt: Instant,
        owner: String?,
    ) = Unit

    override suspend fun markError(
        id: UUID,
        lastError: String,
        lastErrorAt: Instant,
        disable: Boolean,
    ) = Unit

    override suspend fun clearError(
        id: UUID,
        updatedAt: Instant,
    ) = Unit

    override suspend fun claimTelegramUser(
        id: UUID,
        linkSecretHash: String,
        telegramUserId: Long,
        telegramChatId: Long,
        telegramUsername: String?,
        telegramFirstName: String?,
        telegramLastName: String?,
        linkedAt: Instant,
        updatedAt: Instant,
    ): TelegramUserClaimResult = TelegramUserClaimResult.NotFound

    override suspend fun tryAcquireLease(
        id: UUID,
        owner: String,
        leaseUntil: Instant,
        now: Instant,
    ): TelegramBotBinding? = null

    override suspend fun hasActiveLease(
        id: UUID,
        owner: String,
        now: Instant,
    ): Boolean = false
}

private class LeaseLossTelegramBindingRepository(
    private val delegate: MemoryTelegramBotBindingRepository,
) : TelegramBotBindingRepository by delegate {
    var leaseValid: Boolean = true

    override suspend fun hasActiveLease(
        id: UUID,
        owner: String,
        now: Instant,
    ): Boolean = leaseValid && delegate.hasActiveLease(id, owner, now)
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun schedulerClock(scope: TestScope): Clock =
    object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant =
            Instant.parse("2026-05-04T10:00:00Z").plusMillis(scope.testScheduler.currentTime)
    }

private const val TEST_TELEGRAM_TOKEN_ENCRYPTION_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
