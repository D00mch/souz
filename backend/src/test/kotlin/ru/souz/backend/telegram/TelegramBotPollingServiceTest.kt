package ru.souz.backend.telegram

import io.ktor.http.HttpStatusCode
import java.io.IOException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import ru.souz.backend.http.BackendV1Exception
import ru.souz.backend.storage.memory.MemoryTelegramBotBindingRepository

class TelegramBotPollingServiceTest {
    @Test
    fun `telegram text update executes chat turn with trimmed text and telegram client id`() = runTest {
        val repository = MemoryTelegramBotBindingRepository()
        val botApi = FakePollingTelegramBotApi()
        val executor = RecordingTelegramTurnExecutor()
        val service = pollingService(repository, botApi, executor)
        val binding = repository.upsertForChat(
            userId = "user-a",
            chatId = UUID.randomUUID(),
            botToken = "123456:poll-token",
            botTokenHash = sha256("123456:poll-token"),
            now = java.time.Instant.parse("2026-05-04T10:00:00Z"),
        )
        botApi.enqueueUpdates(
            token = binding.botToken,
            updater = { offset ->
                assertEquals(1L, offset)
                TelegramUpdatesResponse(
                    ok = true,
                    result = listOf(
                        TelegramUpdate(
                            updateId = 15L,
                            message = TelegramMessage(
                                messageId = 1L,
                                chat = TelegramChat(id = 777L, type = "private"),
                                text = "  hello from telegram  ",
                            ),
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
                    clientMessageId = "telegram:15",
                    observedLastUpdateId = 15L,
                )
            ),
            executor.calls,
        )
        assertEquals(listOf(SentTelegramMessage(777L, "Принял, выполняю.")), botApi.sentMessages)
        assertEquals(listOf(GetUpdatesCall(binding.botToken, 1L, 30, listOf("message"))), botApi.getUpdatesCalls)
    }

    @Test
    fun `blank telegram text still advances update id and skips execution`() = runTest {
        val repository = MemoryTelegramBotBindingRepository()
        val botApi = FakePollingTelegramBotApi()
        val executor = RecordingTelegramTurnExecutor()
        val service = pollingService(repository, botApi, executor)
        val binding = repository.upsertForChat(
            userId = "user-a",
            chatId = UUID.randomUUID(),
            botToken = "123456:blank-token",
            botTokenHash = sha256("123456:blank-token"),
            now = java.time.Instant.parse("2026-05-04T10:00:00Z"),
        )
        botApi.enqueueUpdates(
            token = binding.botToken,
            updater = {
                TelegramUpdatesResponse(
                    ok = true,
                    result = listOf(
                        TelegramUpdate(
                            updateId = 22L,
                            message = TelegramMessage(
                                messageId = 2L,
                                chat = TelegramChat(id = 888L, type = "private"),
                                text = "   ",
                            ),
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
    fun `repeated polling does not duplicate update after last update id advances`() = runTest {
        val repository = MemoryTelegramBotBindingRepository()
        val botApi = FakePollingTelegramBotApi()
        val executor = RecordingTelegramTurnExecutor()
        val service = pollingService(repository, botApi, executor)
        val binding = repository.upsertForChat(
            userId = "user-a",
            chatId = UUID.randomUUID(),
            botToken = "123456:repeat-token",
            botTokenHash = sha256("123456:repeat-token"),
            now = java.time.Instant.parse("2026-05-04T10:00:00Z"),
        )
        botApi.enqueueUpdates(
            token = binding.botToken,
            updater = { offset ->
                if (offset == null || offset <= 15L) {
                    TelegramUpdatesResponse(
                        ok = true,
                        result = listOf(
                            TelegramUpdate(
                                updateId = 15L,
                                message = TelegramMessage(
                                    messageId = 3L,
                                    chat = TelegramChat(id = 999L, type = "private"),
                                    text = "repeat once",
                                ),
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
        val binding = repository.upsertForChat(
            userId = "user-a",
            chatId = UUID.randomUUID(),
            botToken = "123456:busy-token",
            botTokenHash = sha256("123456:busy-token"),
            now = java.time.Instant.parse("2026-05-04T10:00:00Z"),
        )
        botApi.enqueueSingleTextUpdate(binding.botToken, updateId = 19L, chatId = 1234L, text = "do work")

        service.pollEnabledOnce()

        assertEquals(
            listOf(SentTelegramMessage(1234L, "В этом чате уже выполняется задача. Попробуй позже.")),
            botApi.sentMessages,
        )
    }

    @Test
    fun `execution failure sends generic reply`() = runTest {
        val repository = MemoryTelegramBotBindingRepository()
        val botApi = FakePollingTelegramBotApi()
        val executor = RecordingTelegramTurnExecutor(failure = IllegalStateException("boom"))
        val service = pollingService(repository, botApi, executor)
        val binding = repository.upsertForChat(
            userId = "user-a",
            chatId = UUID.randomUUID(),
            botToken = "123456:fail-token",
            botTokenHash = sha256("123456:fail-token"),
            now = java.time.Instant.parse("2026-05-04T10:00:00Z"),
        )
        botApi.enqueueSingleTextUpdate(binding.botToken, updateId = 20L, chatId = 1235L, text = "do work")

        service.pollEnabledOnce()

        assertEquals(
            listOf(SentTelegramMessage(1235L, "Не удалось выполнить команду.")),
            botApi.sentMessages,
        )
    }

    @Test
    fun `telegram unauthorized disables binding and stores stable error`() = runTest {
        val repository = MemoryTelegramBotBindingRepository()
        val botApi = FakePollingTelegramBotApi()
        val executor = RecordingTelegramTurnExecutor()
        val service = pollingService(repository, botApi, executor)
        val binding = repository.upsertForChat(
            userId = "user-a",
            chatId = UUID.randomUUID(),
            botToken = "123456:unauthorized-token",
            botTokenHash = sha256("123456:unauthorized-token"),
            now = java.time.Instant.parse("2026-05-04T10:00:00Z"),
        )
        botApi.enqueueUpdates(
            token = binding.botToken,
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
        val executor = RecordingTelegramTurnExecutor()
        val service = pollingService(repository, botApi, executor)
        val failingBinding = repository.upsertForChat(
            userId = "user-a",
            chatId = UUID.randomUUID(),
            botToken = "123456:failing-binding",
            botTokenHash = sha256("123456:failing-binding"),
            now = java.time.Instant.parse("2026-05-04T10:00:00Z"),
        )
        val healthyBinding = repository.upsertForChat(
            userId = "user-b",
            chatId = UUID.randomUUID(),
            botToken = "123456:healthy-binding",
            botTokenHash = sha256("123456:healthy-binding"),
            now = java.time.Instant.parse("2026-05-04T10:00:00Z"),
        )
        botApi.enqueueFailure(
            token = failingBinding.botToken,
            error = IOException("network"),
        )
        botApi.enqueueSingleTextUpdate(
            token = healthyBinding.botToken,
            updateId = 31L,
            chatId = 4444L,
            text = "healthy update",
        )

        service.pollEnabledOnce()

        assertEquals(1, executor.calls.size)
        assertEquals(healthyBinding.chatId, executor.calls.single().chatId)
        assertEquals("telegram_network_error", repository.getByChat(failingBinding.chatId)?.lastError)
        assertEquals(
            listOf(SentTelegramMessage(4444L, "Принял, выполняю.")),
            botApi.sentMessages,
        )
    }
}

private fun pollingService(
    repository: MemoryTelegramBotBindingRepository,
    botApi: FakePollingTelegramBotApi,
    executor: RecordingTelegramTurnExecutor,
): TelegramBotPollingService {
    executor.repository = repository
    return TelegramBotPollingService(
        repository = repository,
        botApi = botApi,
        turnExecutor = executor,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )
}

private data class TelegramTurnCall(
    val userId: String,
    val chatId: UUID,
    val content: String,
    val clientMessageId: String,
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
    private val failure: Throwable? = null,
) : TelegramTurnExecutor {
    val calls = mutableListOf<TelegramTurnCall>()
    lateinit var repository: MemoryTelegramBotBindingRepository

    override suspend fun execute(
        userId: String,
        chatId: UUID,
        content: String,
        clientMessageId: String,
    ) {
        calls += TelegramTurnCall(
            userId = userId,
            chatId = chatId,
            content = content,
            clientMessageId = clientMessageId,
            observedLastUpdateId = repository.getByChat(chatId)?.lastUpdateId,
        )
        failure?.let { throw it }
    }
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
        text: String,
    ) {
        enqueueUpdates(token) {
            TelegramUpdatesResponse(
                ok = true,
                result = listOf(
                    TelegramUpdate(
                        updateId = updateId,
                        message = TelegramMessage(
                            messageId = updateId,
                            chat = TelegramChat(id = chatId, type = "private"),
                            text = text,
                        ),
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

    override suspend fun sendMessage(
        token: String,
        chatId: Long,
        text: String,
    ) {
        sentMessages += SentTelegramMessage(chatId, text)
    }
}
