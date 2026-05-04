package ru.souz.backend.telegram

import io.ktor.http.HttpStatusCode
import java.io.IOException
import java.net.InetAddress
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import ru.souz.backend.chat.service.SendMessageResult
import ru.souz.backend.execution.service.AgentExecutionService
import ru.souz.backend.http.BackendV1Exception
import ru.souz.backend.settings.service.UserSettingsOverrides

fun interface TelegramTurnExecutor {
    suspend fun execute(
        userId: String,
        chatId: UUID,
        content: String,
        clientMessageId: String,
        requestOverrides: UserSettingsOverrides,
    ): SendMessageResult
}

private class AgentExecutionTelegramTurnExecutor(
    private val executionService: AgentExecutionService,
) : TelegramTurnExecutor {
    override suspend fun execute(
        userId: String,
        chatId: UUID,
        content: String,
        clientMessageId: String,
        requestOverrides: UserSettingsOverrides,
    ): SendMessageResult =
        executionService.executeChatTurn(
            userId = userId,
            chatId = chatId,
            content = content,
            clientMessageId = clientMessageId,
            requestOverrides = requestOverrides,
        )
}

class TelegramBotPollingService(
    private val repository: TelegramBotBindingRepository,
    private val botApi: TelegramBotApi,
    private val turnExecutor: TelegramTurnExecutor,
    private val tokenCrypto: TelegramBotTokenCrypto,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
    private val instanceId: String = defaultInstanceId(),
    private val pollLoopDelayMs: Long = POLL_LOOP_DELAY_MS,
    private val leaseTtlSeconds: Long = LEASE_TTL_SECONDS,
    maxConcurrency: Int = DEFAULT_MAX_CONCURRENCY,
    private val maxIncomingTextLength: Int = MAX_INCOMING_TEXT_LENGTH,
) {
    private val logger = LoggerFactory.getLogger(TelegramBotPollingService::class.java)
    private val semaphore = Semaphore(maxConcurrency)
    private var pollingJob: Job? = null

    constructor(
        repository: TelegramBotBindingRepository,
        botApi: TelegramBotApi,
        executionService: AgentExecutionService,
        tokenCrypto: TelegramBotTokenCrypto,
        scope: CoroutineScope,
        clock: Clock = Clock.systemUTC(),
        instanceId: String = defaultInstanceId(),
        pollLoopDelayMs: Long = POLL_LOOP_DELAY_MS,
        leaseTtlSeconds: Long = LEASE_TTL_SECONDS,
        maxConcurrency: Int = DEFAULT_MAX_CONCURRENCY,
        maxIncomingTextLength: Int = MAX_INCOMING_TEXT_LENGTH,
    ) : this(
        repository = repository,
        botApi = botApi,
        turnExecutor = AgentExecutionTelegramTurnExecutor(executionService),
        tokenCrypto = tokenCrypto,
        scope = scope,
        clock = clock,
        instanceId = instanceId,
        pollLoopDelayMs = pollLoopDelayMs,
        leaseTtlSeconds = leaseTtlSeconds,
        maxConcurrency = maxConcurrency,
        maxIncomingTextLength = maxIncomingTextLength,
    )

    fun start() {
        if (pollingJob?.isActive == true) {
            return
        }
        pollingJob = scope.launch {
            while (isActive) {
                try {
                    pollEnabledOnce()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn("Telegram polling loop iteration failed: {}", e.message)
                }
                delay(pollLoopDelayMs)
            }
        }
    }

    internal suspend fun pollEnabledOnce() {
        val bindings = repository.listEnabled()
        supervisorScope {
            bindings.forEach { binding ->
                launch {
                    semaphore.withPermit {
                        pollBinding(binding)
                    }
                }
            }
        }
    }

    private suspend fun pollBinding(binding: TelegramBotBinding) {
        val now = clock.instant()
        val leasedBinding = repository.tryAcquireLease(
            id = binding.id,
            owner = instanceId,
            leaseUntil = now.plusSeconds(leaseTtlSeconds),
            now = now,
        ) ?: return
        val token = try {
            tokenCrypto.decrypt(leasedBinding.botTokenEncrypted)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            repository.markError(leasedBinding.id, TELEGRAM_TOKEN_DECRYPT_ERROR)
            logger.warn("Telegram token decrypt failed for binding {}", leasedBinding.id)
            return
        }

        val updates = try {
            botApi.getUpdates(
                token = token,
                offset = leasedBinding.lastUpdateId + 1L,
                timeoutSeconds = GET_UPDATES_TIMEOUT_SECONDS,
                allowedUpdates = listOf("message"),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: TelegramBotApiHttpException) {
            handleHttpError(leasedBinding, e)
            return
        } catch (e: TelegramBotApiTransportException) {
            repository.markError(leasedBinding.id, TELEGRAM_NETWORK_ERROR)
            logger.warn("Telegram long polling transport failure for binding {}", leasedBinding.id)
            return
        } catch (e: IOException) {
            repository.markError(leasedBinding.id, TELEGRAM_NETWORK_ERROR)
            logger.warn("Telegram long polling IO failure for binding {}", leasedBinding.id)
            return
        } catch (e: Exception) {
            repository.markError(leasedBinding.id, TELEGRAM_UNKNOWN_ERROR)
            logger.warn("Telegram long polling unexpected failure for binding {}", leasedBinding.id)
            return
        }

        if (!updates.ok) {
            handleResponseError(leasedBinding, updates)
            return
        }

        repository.clearError(leasedBinding.id)
        var currentBinding = leasedBinding
        for (update in updates.result) {
            currentBinding = handleUpdate(currentBinding, token, update)
            repository.updateLastUpdateId(currentBinding.id, update.updateId)
        }
    }

    private suspend fun handleUpdate(
        binding: TelegramBotBinding,
        token: String,
        update: TelegramUpdate,
    ): TelegramBotBinding {
        val message = update.message ?: return binding
        val chatType = message.chat.type
        val sender = message.from

        if (!binding.linked) {
            if (chatType != PRIVATE_CHAT_TYPE || sender == null) {
                return binding
            }
            val linkedBinding = repository.linkTelegramUser(
                id = binding.id,
                telegramUserId = sender.id,
                telegramChatId = message.chat.id,
                telegramUsername = sender.username,
                telegramFirstName = sender.firstName,
                telegramLastName = sender.lastName,
                linkedAt = clock.instant(),
            ) ?: binding
            sendReplySafely(token, message.chat.id, LINKED_REPLY)
            return linkedBinding
        }

        val senderMatches = chatType == PRIVATE_CHAT_TYPE &&
            sender?.id == binding.telegramUserId &&
            message.chat.id == binding.telegramChatId
        if (!senderMatches) {
            if (chatType == PRIVATE_CHAT_TYPE) {
                sendReplySafely(token, message.chat.id, ALREADY_BOUND_REPLY)
            }
            return binding
        }

        val text = message.text?.trim().orEmpty()
        if (text.isBlank()) {
            return binding
        }
        if (text.length > maxIncomingTextLength) {
            sendReplySafely(token, message.chat.id, TOO_LONG_REPLY)
            return binding
        }

        try {
            val result = turnExecutor.execute(
                userId = binding.userId,
                chatId = binding.chatId,
                content = text,
                clientMessageId = "telegram:${binding.id}:${update.updateId}",
                requestOverrides = UserSettingsOverrides(streamingMessages = false),
            )
            sendAssistantReply(token, message.chat.id, result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackendV1Exception) {
            if (e.code == "chat_already_has_active_execution") {
                sendReplySafely(token, message.chat.id, ACTIVE_EXECUTION_REPLY)
            } else {
                logger.warn("Telegram turn execution failed with v1 code {} for binding {}", e.code, binding.id)
                sendReplySafely(token, message.chat.id, GENERIC_FAILURE_REPLY)
            }
        } catch (e: Exception) {
            logger.warn("Telegram turn execution failed for binding {}", binding.id)
            sendReplySafely(token, message.chat.id, GENERIC_FAILURE_REPLY)
        }
        return binding
    }

    private suspend fun sendAssistantReply(
        token: String,
        chatId: Long,
        result: SendMessageResult,
    ) {
        val responseText = result.assistantMessage?.content.orEmpty()
        val chunks = telegramTextChunks(
            text = responseText.ifBlank { FALLBACK_ASSISTANT_REPLY },
            maxLength = TELEGRAM_TEXT_LIMIT,
        )
        chunks.forEach { chunk ->
            sendReplySafely(token, chatId, chunk)
        }
    }

    private suspend fun handleResponseError(
        binding: TelegramBotBinding,
        response: TelegramUpdatesResponse,
    ) {
        when (response.errorCode) {
            HttpStatusCode.Unauthorized.value,
            HttpStatusCode.Forbidden.value,
            -> repository.markError(binding.id, TELEGRAM_UNAUTHORIZED, disable = true)

            HttpStatusCode.Conflict.value ->
                repository.markError(binding.id, TELEGRAM_CONFLICT_WEBHOOK_ENABLED)

            HttpStatusCode.TooManyRequests.value -> {
                repository.markError(binding.id, TELEGRAM_RATE_LIMITED)
                response.parameters?.retryAfter?.takeIf { it > 0 }?.let { delay(it * 1_000L) }
            }

            else -> repository.markError(binding.id, TELEGRAM_UNKNOWN_ERROR)
        }
    }

    private suspend fun handleHttpError(
        binding: TelegramBotBinding,
        error: TelegramBotApiHttpException,
    ) {
        when (error.telegramErrorCode ?: error.statusCode) {
            HttpStatusCode.Unauthorized.value,
            HttpStatusCode.Forbidden.value,
            -> repository.markError(binding.id, TELEGRAM_UNAUTHORIZED, disable = true)

            HttpStatusCode.Conflict.value ->
                repository.markError(binding.id, TELEGRAM_CONFLICT_WEBHOOK_ENABLED)

            HttpStatusCode.TooManyRequests.value -> {
                repository.markError(binding.id, TELEGRAM_RATE_LIMITED)
                error.parameters?.retryAfter?.takeIf { it > 0 }?.let { delay(it * 1_000L) }
            }

            else -> repository.markError(binding.id, TELEGRAM_UNKNOWN_ERROR)
        }
    }

    private suspend fun sendReplySafely(
        token: String,
        chatId: Long,
        text: String,
    ) {
        try {
            botApi.sendMessage(token = token, chatId = chatId, text = text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Telegram reply send failed for chat {}", chatId)
        }
    }

    private companion object {
        const val GET_UPDATES_TIMEOUT_SECONDS: Int = 30
        const val POLL_LOOP_DELAY_MS: Long = 1_000L
        const val LEASE_TTL_SECONDS: Long = 45L
        const val DEFAULT_MAX_CONCURRENCY: Int = 4
        const val MAX_INCOMING_TEXT_LENGTH: Int = 8_000
        const val TELEGRAM_TEXT_LIMIT: Int = 4_096
        const val PRIVATE_CHAT_TYPE: String = "private"

        const val LINKED_REPLY: String = "Готово, этот Telegram-аккаунт привязан к чату Souz."
        const val FALLBACK_ASSISTANT_REPLY: String = "Готово."
        const val ACTIVE_EXECUTION_REPLY: String = "В этом чате уже выполняется задача. Попробуй позже."
        const val GENERIC_FAILURE_REPLY: String = "Не удалось выполнить команду."
        const val ALREADY_BOUND_REPLY: String = "Этот бот уже привязан к другому Telegram-аккаунту."
        const val TOO_LONG_REPLY: String = "Сообщение слишком длинное."

        const val TELEGRAM_UNAUTHORIZED: String = "telegram_unauthorized"
        const val TELEGRAM_CONFLICT_WEBHOOK_ENABLED: String = "telegram_conflict_webhook_enabled"
        const val TELEGRAM_RATE_LIMITED: String = "telegram_rate_limited"
        const val TELEGRAM_NETWORK_ERROR: String = "telegram_network_error"
        const val TELEGRAM_UNKNOWN_ERROR: String = "telegram_unknown_error"
        const val TELEGRAM_TOKEN_DECRYPT_ERROR: String = "telegram_token_decrypt_error"

        fun telegramTextChunks(
            text: String,
            maxLength: Int,
        ): List<String> {
            if (text.isBlank()) {
                return listOf(FALLBACK_ASSISTANT_REPLY)
            }
            if (text.length <= maxLength) {
                return listOf(text)
            }
            val chunks = mutableListOf<String>()
            var start = 0
            while (start < text.length) {
                val remaining = text.length - start
                if (remaining <= maxLength) {
                    chunks += text.substring(start)
                    break
                }
                val hardEnd = start + maxLength
                val splitAt = text.lastIndexOf('\n', hardEnd - 1, ignoreCase = false)
                    .takeIf { it >= start + maxLength / 2 }
                    ?: text.lastIndexOf(' ', hardEnd - 1, ignoreCase = false)
                        .takeIf { it >= start + maxLength / 2 }
                    ?: hardEnd
                val endExclusive = if (splitAt == hardEnd) hardEnd else splitAt + 1
                chunks += text.substring(start, endExclusive)
                start = endExclusive
            }
            return chunks
        }

        fun defaultInstanceId(): String {
            val host = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("unknown-host")
            return "$host:${UUID.randomUUID()}"
        }
    }
}
