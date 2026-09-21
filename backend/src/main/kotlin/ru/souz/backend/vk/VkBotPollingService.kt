package ru.souz.backend.vk

import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import ru.souz.backend.channels.channelTextChunks
import ru.souz.backend.channels.pollBindings
import ru.souz.backend.crypto.sha256Hex
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.execution.service.AgentExecutionService
import ru.souz.backend.http.BackendV1Exception
import ru.souz.backend.settings.service.UserSettingsOverrides
import ru.souz.backend.storage.postgres.PostgresVkBotBindingRepository

class VkBotPollingService(
    private val repository: PostgresVkBotBindingRepository,
    private val botApi: VkBotApi,
    private val executionService: AgentExecutionService,
    private val tokenCrypto: VkBotTokenCrypto,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
    private val pollLoopDelayMs: Long = 1_000,
    private val leaseTtlSeconds: Long = 45,
    maxConcurrency: Int = 4,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val owner = UUID.randomUUID().toString()
    private val semaphore = Semaphore(maxConcurrency)
    private var pollingJob: Job? = null

    internal class PollSession {
        var server: VkLongPollServer? = null
        var lastRejectionAt: Instant = Instant.MIN
    }

    fun start() {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch {
            pollBindings(pollLoopDelayMs, logger) {
                repository.listEnabled().associate { binding ->
                    val session = PollSession()
                    binding.id to suspend { pollBinding(binding.id, session) }
                }
            }
        }
    }

    internal suspend fun pollBinding(id: UUID, session: PollSession) = coroutineScope {
        val now = clock.instant()
        var current = repository.tryAcquireLease(id, owner, now.plusSeconds(leaseTtlSeconds), now)
            ?: return@coroutineScope
        val bindingScope = this
        val heartbeat = launch {
            while (isActive) {
                delay((leaseTtlSeconds * 1_000 / 3).coerceAtLeast(100))
                val tick = clock.instant()
                if (!repository.renewLease(id, owner, tick.plusSeconds(leaseTtlSeconds), tick)) {
                    bindingScope.cancel("Lost VK binding lease.")
                }
            }
        }
        try {
            val token = try {
                tokenCrypto.decrypt(current.groupTokenEncrypted)
            } catch (_: Exception) {
                repository.markError(current.id, owner, "vk_token_decrypt_error", clock.instant())
                return@coroutineScope
            }
            val batch = fetchBatch(current, token, session)
            for (update in batch.updates) {
                semaphore.withPermit {
                    if (!owns(current.id)) return@coroutineScope
                    current = handleUpdate(current, token, update, session)
                }
            }
            repository.updateLastTs(current.id, owner, requireNotNull(batch.ts), clock.instant())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val code = (e as? VkBotApiException)?.code
            val error = when {
                code == 5 || code == 15 -> "vk_unauthorized"
                code in listOf(6, 9, 29) -> "vk_rate_limited"
                code == 10 || e is IOException -> "vk_network_error"
                else -> "vk_unknown_error"
            }
            repository.markError(id, owner, error, clock.instant(), disable = code == 5 || code == 15)
            if (error == "vk_rate_limited") delay(5_000)
        } finally {
            heartbeat.cancelAndJoin()
        }
    }

    private suspend fun fetchBatch(binding: VkBotBinding, token: String, session: PollSession): VkLongPollResponse {
        var server = session.server ?: botApi.getLongPollServer(token, binding.vkGroupId).also { session.server = it }
        var ts = binding.lastTs ?: server.ts
        // Persist the initial cursor before processing so a failed first batch can be replayed.
        if (binding.lastTs == null) repository.updateLastTs(binding.id, owner, ts, clock.instant())
        repeat(3) { attempt ->
            val response = botApi.pollLongPoll(server.server, server.key, ts)
            when (response.failed) {
                null -> return response.copy(ts = response.ts ?: ts)
                1 -> ts = response.ts ?: throw IOException("Missing VK Long Poll timestamp.")
                2, 3 -> {
                    server = botApi.getLongPollServer(token, binding.vkGroupId)
                    session.server = server
                    if (response.failed == 3) ts = server.ts
                }
                else -> throw IOException("Unsupported VK Long Poll failure.")
            }
            if (attempt < 2) delay(500)
        }
        throw IOException("VK Long Poll retry limit reached.")
    }

    private suspend fun handleUpdate(
        binding: VkBotBinding,
        token: String,
        update: VkLongPollUpdate,
        session: PollSession,
    ): VkBotBinding {
        val message = update.obj?.message ?: return binding
        if (update.type != "message_new" || message.out != 0 || message.fromId <= 0 || message.peerId != message.fromId) {
            return binding
        }
        val text = message.text?.trim().orEmpty()
        if (text.isEmpty()) return binding
        if (!binding.linked) {
            val secretHash = text.sha256Hex()
            if (binding.linkSecretHash != secretHash) {
                reply(binding, token, message.peerId, "Чтобы привязать этот чат, отправь секрет, который показал Souz.")
                return binding
            }
            val profile = try {
                botApi.getUserInfo(token, message.fromId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val linked = repository.claimVkUser(
                binding.id, owner, secretHash, message.id, message.fromId, message.peerId,
                profile?.firstName, profile?.lastName, clock.instant(),
            ) ?: return binding
            reply(linked, token, message.peerId, "Готово, этот VK-аккаунт привязан к чату Souz.")
            return linked
        }
        if (message.fromId != binding.vkUserId || message.peerId != binding.vkPeerId) {
            if (clock.instant().isAfter(session.lastRejectionAt.plusSeconds(60))) {
                reply(binding, token, message.peerId, "Этот бот уже привязан к другому VK-аккаунту.")
                session.lastRejectionAt = clock.instant()
            }
            return binding
        }
        if (message.id <= (binding.linkedMessageId ?: 0L)) return binding
        if (text.length > 8_000) {
            reply(binding, token, message.peerId, "Сообщение слишком длинное.")
            return binding
        }
        val responseText = try {
            val result = coroutineScope {
                val typing = launch {
                    while (isActive && owns(binding.id)) {
                        try {
                            botApi.setActivity(token, message.peerId, binding.vkGroupId)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // Typing is best effort; it must not cancel the turn.
                        }
                        delay(4_000)
                    }
                }
                try {
                    executionService.executeChatTurnAndAwaitCompletion(
                        userId = binding.userId,
                        chatId = binding.chatId,
                        content = text,
                        clientMessageId = "vk:${binding.id}:${message.id}",
                        requestOverrides = UserSettingsOverrides(streamingMessages = false),
                    )
                } finally {
                    typing.cancelAndJoin()
                }
            }
            result.assistantMessage?.content ?: when (result.execution.status) {
                AgentExecutionStatus.COMPLETED -> "Готово."
                AgentExecutionStatus.RUNNING -> ACTIVE_EXECUTION_REPLY
                else -> FAILURE_REPLY
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackendV1Exception) {
            if (e.code == "chat_already_has_active_execution") ACTIVE_EXECUTION_REPLY else FAILURE_REPLY
        } catch (_: Exception) {
            logger.warn("VK turn failed for binding {}", binding.id)
            FAILURE_REPLY
        }
        for (chunk in channelTextChunks(responseText.ifBlank { "Готово." })) {
            reply(binding, token, message.peerId, chunk)
        }
        return binding
    }

    private suspend fun owns(id: UUID): Boolean = repository.hasActiveLease(id, owner, clock.instant())

    private suspend fun reply(binding: VkBotBinding, token: String, peerId: Long, text: String) {
        if (!owns(binding.id)) throw CancellationException("Lost VK binding lease.")
        // Delivery errors leave the batch cursor unchanged for retry.
        botApi.sendMessage(token, peerId, text)
    }

    private companion object {
        const val ACTIVE_EXECUTION_REPLY = "В этом чате уже выполняется задача. Попробуй позже."
        const val FAILURE_REPLY = "Не удалось выполнить команду."
    }
}
