package ru.souz.backend.telegram

import io.ktor.http.HttpStatusCode
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.slf4j.LoggerFactory
import ru.souz.backend.execution.service.AgentExecutionService
import ru.souz.backend.http.BackendV1Exception

fun interface TelegramTurnExecutor {
    suspend fun execute(
        userId: String,
        chatId: UUID,
        content: String,
        clientMessageId: String,
    )
}

private class AgentExecutionTelegramTurnExecutor(
    private val executionService: AgentExecutionService,
) : TelegramTurnExecutor {
    override suspend fun execute(
        userId: String,
        chatId: UUID,
        content: String,
        clientMessageId: String,
    ) {
        executionService.executeChatTurn(
            userId = userId,
            chatId = chatId,
            content = content,
            clientMessageId = clientMessageId,
        )
    }
}

class TelegramBotPollingService(
    private val repository: TelegramBotBindingRepository,
    private val botApi: TelegramBotApi,
    private val turnExecutor: TelegramTurnExecutor,
    private val scope: CoroutineScope,
) {
    private val logger = LoggerFactory.getLogger(TelegramBotPollingService::class.java)
    private var pollingJob: Job? = null

    constructor(
        repository: TelegramBotBindingRepository,
        botApi: TelegramBotApi,
        executionService: AgentExecutionService,
        scope: CoroutineScope,
    ) : this(
        repository = repository,
        botApi = botApi,
        turnExecutor = AgentExecutionTelegramTurnExecutor(executionService),
        scope = scope,
    )

    fun start() {
        if (pollingJob?.isActive == true) {
            return
        }
        pollingJob = scope.launch {
            while (isActive) {
                pollEnabledOnce()
                delay(POLL_LOOP_DELAY_MS)
            }
        }
    }

    internal suspend fun pollEnabledOnce() {
        val bindings = repository.listEnabled()
        supervisorScope {
            bindings.forEach { binding ->
                launch {
                    pollBinding(binding)
                }
            }
        }
    }

    private suspend fun pollBinding(binding: TelegramBotBinding) {
        val updates = try {
            botApi.getUpdates(
                token = binding.botToken,
                offset = binding.lastUpdateId + 1L,
                timeoutSeconds = GET_UPDATES_TIMEOUT_SECONDS,
                allowedUpdates = listOf("message"),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: TelegramBotApiHttpException) {
            handleHttpError(binding, e)
            return
        } catch (e: TelegramBotApiTransportException) {
            repository.markError(binding.id, TELEGRAM_NETWORK_ERROR)
            logger.warn("Telegram long polling transport failure for binding {}", binding.id)
            return
        } catch (e: IOException) {
            repository.markError(binding.id, TELEGRAM_NETWORK_ERROR)
            logger.warn("Telegram long polling IO failure for binding {}", binding.id)
            return
        } catch (e: Exception) {
            repository.markError(binding.id, TELEGRAM_UNKNOWN_ERROR)
            logger.warn("Telegram long polling unexpected failure for binding {}", binding.id)
            return
        }

        if (!updates.ok) {
            handleResponseError(binding, updates)
            return
        }

        repository.clearError(binding.id)
        for (update in updates.result) {
            repository.updateLastUpdateId(binding.id, update.updateId)
            val message = update.message ?: continue
            val text = message.text?.trim().orEmpty()
            if (text.isBlank()) {
                continue
            }
            try {
                turnExecutor.execute(
                    userId = binding.userId,
                    chatId = binding.chatId,
                    content = text,
                    clientMessageId = "telegram:${update.updateId}",
                )
                sendReplySafely(binding.botToken, message.chat.id, SUCCESS_REPLY)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackendV1Exception) {
                if (e.code == "chat_already_has_active_execution") {
                    sendReplySafely(binding.botToken, message.chat.id, ACTIVE_EXECUTION_REPLY)
                } else {
                    logger.warn("Telegram turn execution failed with v1 code {} for binding {}", e.code, binding.id)
                    sendReplySafely(binding.botToken, message.chat.id, GENERIC_FAILURE_REPLY)
                }
            } catch (e: Exception) {
                logger.warn("Telegram turn execution failed for binding {}", binding.id)
                sendReplySafely(binding.botToken, message.chat.id, GENERIC_FAILURE_REPLY)
            }
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

        const val SUCCESS_REPLY: String = "Принял, выполняю."
        const val ACTIVE_EXECUTION_REPLY: String = "В этом чате уже выполняется задача. Попробуй позже."
        const val GENERIC_FAILURE_REPLY: String = "Не удалось выполнить команду."

        const val TELEGRAM_UNAUTHORIZED: String = "telegram_unauthorized"
        const val TELEGRAM_CONFLICT_WEBHOOK_ENABLED: String = "telegram_conflict_webhook_enabled"
        const val TELEGRAM_RATE_LIMITED: String = "telegram_rate_limited"
        const val TELEGRAM_NETWORK_ERROR: String = "telegram_network_error"
        const val TELEGRAM_UNKNOWN_ERROR: String = "telegram_unknown_error"
    }
}
