package ru.souz.backend.vk

import java.io.IOException
import java.net.InetAddress
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import ru.souz.backend.chat.service.SendMessageResult
import ru.souz.backend.crypto.sha256Hex
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.execution.service.AgentExecutionService
import ru.souz.backend.http.BackendV1Exception
import ru.souz.backend.settings.service.UserSettingsOverrides
import kotlin.time.Duration.Companion.milliseconds

fun interface VkTurnExecutor {
    suspend fun execute(
        userId: String,
        chatId: UUID,
        content: String,
        clientMessageId: String,
        requestOverrides: UserSettingsOverrides,
    ): SendMessageResult
}

private class AgentExecutionVkTurnExecutor(
    private val executionService: AgentExecutionService,
) : VkTurnExecutor {
    override suspend fun execute(
        userId: String,
        chatId: UUID,
        content: String,
        clientMessageId: String,
        requestOverrides: UserSettingsOverrides,
    ): SendMessageResult =
        executionService.executeChatTurnAndAwaitCompletion(
            userId = userId,
            chatId = chatId,
            content = content,
            clientMessageId = clientMessageId,
            requestOverrides = requestOverrides,
        )
}

private data class VkLongPollBatch(val newTs: String, val updates: List<VkLongPollUpdate>)

class VkBotPollingService(
    private val repository: VkBotBindingRepository,
    private val botApi: VkBotApi,
    private val turnExecutor: VkTurnExecutor,
    private val tokenCrypto: VkBotTokenCrypto,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
    private val instanceId: String = defaultInstanceId(),
    private val pollLoopDelayMs: Long = POLL_LOOP_DELAY_MS,
    private val leaseTtlSeconds: Long = LEASE_TTL_SECONDS,
    maxConcurrency: Int = DEFAULT_MAX_CONCURRENCY,
    private val maxIncomingTextLength: Int = MAX_INCOMING_TEXT_LENGTH,
) {
    private val logger = LoggerFactory.getLogger(VkBotPollingService::class.java)
    private val semaphore = Semaphore(maxConcurrency)
    private var pollingJob: Job? = null
    private val lastAlreadyBoundReplyAt = ConcurrentHashMap<UUID, Instant>()

    // In-memory only — never persisted, and intentionally not part of VkBotBinding. Caches the
    // server/key pair across poll ticks for a binding so a healthy poll loop only calls
    // groups.getLongPollServer once (not once per tick); a stale/invalid cached key self-heals
    // via the ordinary failed=2/3 handling in fetchLongPollBatch, which refreshes and re-caches it.
    private val longPollSessions = ConcurrentHashMap<UUID, VkLongPollServer>()

    constructor(
        repository: VkBotBindingRepository,
        botApi: VkBotApi,
        executionService: AgentExecutionService,
        tokenCrypto: VkBotTokenCrypto,
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
        turnExecutor = AgentExecutionVkTurnExecutor(executionService),
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
                    logger.warn("VK polling loop iteration failed: {}", e.message)
                }
                delay(pollLoopDelayMs.milliseconds)
            }
        }
    }

    internal suspend fun pollEnabledOnce() {
        val bindings = repository.listEnabled()
        longPollSessions.keys.retainAll(bindings.mapTo(HashSet()) { it.id })
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

    private suspend fun pollBinding(binding: VkBotBinding) = coroutineScope {
        val bindingScope = this
        val now = clock.instant()
        val leasedBinding = repository.tryAcquireLease(
            id = binding.id,
            owner = instanceId,
            leaseUntil = now.plusSeconds(leaseTtlSeconds),
            now = now,
        ) ?: return@coroutineScope
        val leaseHeartbeat = launch {
            val renewIntervalMs = leaseRenewIntervalMs(leaseTtlSeconds)
            while (isActive) {
                delay(renewIntervalMs.milliseconds)
                repository.tryAcquireLease(
                    id = leasedBinding.id,
                    owner = instanceId,
                    leaseUntil = clock.instant().plusSeconds(leaseTtlSeconds),
                    now = clock.instant(),
                ) ?: run {
                    bindingScope.cancel(CancellationException("Lost VK lease for binding ${leasedBinding.id}."))
                    return@launch
                }
            }
        }

        try {
            val token = try {
                tokenCrypto.decrypt(leasedBinding.groupTokenEncrypted)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                repository.markError(leasedBinding.id, VK_TOKEN_DECRYPT_ERROR)
                logger.warn("VK token decrypt failed for binding {}", leasedBinding.id)
                return@coroutineScope
            }

            val batch = try {
                fetchLongPollBatch(leasedBinding.id, token, leasedBinding.vkGroupId, leasedBinding.lastTs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: VkBotApiHttpException) {
                handleApiError(leasedBinding, e.vkError)
                return@coroutineScope
            } catch (_: VkBotApiTransportException) {
                repository.markError(leasedBinding.id, VK_NETWORK_ERROR)
                logger.warn("VK long polling transport failure for binding {}", leasedBinding.id)
                return@coroutineScope
            } catch (_: IOException) {
                repository.markError(leasedBinding.id, VK_NETWORK_ERROR)
                logger.warn("VK long polling IO failure for binding {}", leasedBinding.id)
                return@coroutineScope
            } catch (_: Exception) {
                repository.markError(leasedBinding.id, VK_UNKNOWN_ERROR)
                logger.warn("VK long polling unexpected failure for binding {}", leasedBinding.id)
                return@coroutineScope
            }

            if (!repository.hasActiveLease(leasedBinding.id, instanceId, clock.instant())) {
                return@coroutineScope
            }
            repository.clearError(leasedBinding.id)
            var currentBinding = leasedBinding
            for (update in batch.updates) {
                if (!repository.hasActiveLease(currentBinding.id, instanceId, clock.instant())) {
                    return@coroutineScope
                }
                currentBinding = handleUpdate(currentBinding, token, update)
            }
            if (repository.hasActiveLease(currentBinding.id, instanceId, clock.instant())) {
                repository.updateLastTs(
                    id = currentBinding.id,
                    lastTs = batch.newTs,
                    owner = instanceId,
                )
            }
        } finally {
            leaseHeartbeat.cancelAndJoin()
        }
    }

    /**
     * One Long Poll round for [groupId]: reuses the cached session for [bindingId] if this
     * instance already has one (avoiding a `groups.getLongPollServer` call on every healthy poll
     * tick), or negotiates a fresh one. Resumes from [lastTs] when present. Retries in-place per
     * VK's `failed` codes — `1` resumes with the server-returned `ts`, `2` refreshes only the key,
     * `3` resets the whole session — up to [MAX_LONGPOLL_ATTEMPTS] before giving up for this poll
     * tick; a stale cached key self-heals via the `2`/`3` paths, which re-cache the fresh session.
     */
    private suspend fun fetchLongPollBatch(bindingId: UUID, token: String, groupId: Long, lastTs: String?): VkLongPollBatch {
        var session = longPollSessions[bindingId]
            ?: negotiateSession(token, groupId).also { longPollSessions[bindingId] = it }
        var ts = lastTs ?: session.ts
        var attempts = 0
        while (attempts < MAX_LONGPOLL_ATTEMPTS) {
            attempts++
            val response = botApi.pollLongPoll(session.server, session.key, ts, waitSeconds = LONGPOLL_WAIT_SECONDS)
            when (response.failed) {
                null -> return VkLongPollBatch(newTs = response.ts ?: ts, updates = response.updates)
                1 -> ts = response.ts ?: ts
                2 -> {
                    session = negotiateSession(token, groupId).let { fresh -> session.copy(server = fresh.server, key = fresh.key) }
                    longPollSessions[bindingId] = session
                }
                3 -> {
                    session = negotiateSession(token, groupId)
                    ts = session.ts
                    longPollSessions[bindingId] = session
                }
                else -> throw VkBotApiException("Unknown VK Long Poll failed code: ${response.failed}")
            }
            // A short pause between retries so a VK-side blip returning failed=1/2/3 repeatedly
            // doesn't fire up to MAX_LONGPOLL_ATTEMPTS requests back-to-back with no backoff.
            if (attempts < MAX_LONGPOLL_ATTEMPTS) {
                delay(LONGPOLL_RETRY_DELAY_MS.milliseconds)
            }
        }
        throw VkBotApiException("VK Long Poll session could not be established after $attempts attempt(s).")
    }

    private suspend fun negotiateSession(token: String, groupId: Long): VkLongPollServer {
        val response = botApi.getLongPollServer(token, groupId)
        val server = response.response
        if (response.error != null || server == null) {
            throw VkBotApiHttpException(
                methodName = "groups.getLongPollServer",
                vkError = response.error ?: VkApiError(errorMsg = "Empty VK Long Poll server response."),
            )
        }
        return server
    }

    private suspend fun handleUpdate(
        binding: VkBotBinding,
        token: String,
        update: VkLongPollUpdate,
    ): VkBotBinding {
        if (update.type != MESSAGE_NEW_EVENT) {
            return binding
        }
        val message = update.obj?.message ?: return binding
        val isDirect = message.peerId == message.fromId
        val text = message.text?.trim().orEmpty()

        if (!binding.linked) {
            if (!isDirect || text.isBlank()) {
                return binding
            }
            val profile = fetchUserProfile(token, message.fromId)
            return when (
                val claim = repository.claimVkUser(
                    id = binding.id,
                    linkSecretHash = sha256Hex(text),
                    vkUserId = message.fromId,
                    vkPeerId = message.peerId,
                    vkFirstName = profile?.firstName,
                    vkLastName = profile?.lastName,
                    linkedAt = clock.instant(),
                )
            ) {
                is VkUserClaimResult.Claimed -> {
                    sendReplySafely(binding.id, token, message.peerId, LINKED_REPLY)
                    claim.binding
                }

                is VkUserClaimResult.InvalidSecret -> {
                    sendReplySafely(binding.id, token, message.peerId, PENDING_LINK_REPLY)
                    claim.binding
                }

                is VkUserClaimResult.AlreadyLinked -> {
                    val sameSender = message.fromId == claim.binding.vkUserId &&
                        message.peerId == claim.binding.vkPeerId
                    if (!sameSender) {
                        sendAlreadyBoundReplyThrottled(binding.id, token, message.peerId)
                    }
                    claim.binding
                }

                VkUserClaimResult.NotFound -> binding
            }
        }

        val senderMatches = isDirect &&
            message.fromId == binding.vkUserId &&
            message.peerId == binding.vkPeerId
        if (!senderMatches) {
            if (isDirect) {
                sendAlreadyBoundReplyThrottled(binding.id, token, message.peerId)
            }
            return binding
        }

        if (text.isBlank()) {
            return binding
        }
        if (text.length > maxIncomingTextLength) {
            sendReplySafely(binding.id, token, message.peerId, TOO_LONG_REPLY)
            return binding
        }

        try {
            val result = coroutineScope {
                val typingJob = launch { repeatTypingIndicator(binding.id, token, message.peerId, binding.vkGroupId) }
                try {
                    turnExecutor.execute(
                        userId = binding.userId,
                        chatId = binding.chatId,
                        content = text,
                        clientMessageId = "vk:${binding.id}:${message.id}",
                        requestOverrides = UserSettingsOverrides(streamingMessages = false),
                    )
                } finally {
                    typingJob.cancelAndJoin()
                }
            }
            sendAssistantReply(binding.id, token, message.peerId, result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackendV1Exception) {
            if (e.code == "chat_already_has_active_execution") {
                sendReplySafely(binding.id, token, message.peerId, ACTIVE_EXECUTION_REPLY)
            } else {
                logger.warn("VK turn execution failed with v1 code {} for binding {}", e.code, binding.id)
                sendReplySafely(binding.id, token, message.peerId, GENERIC_FAILURE_REPLY)
            }
        } catch (_: Exception) {
            logger.warn("VK turn execution failed for binding {}", binding.id)
            sendReplySafely(binding.id, token, message.peerId, GENERIC_FAILURE_REPLY)
        }
        return binding
    }

    private suspend fun fetchUserProfile(token: String, userId: Long): VkUser? =
        try {
            botApi.getUserInfo(token, userId).response?.firstOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    private suspend fun sendAssistantReply(
        bindingId: UUID,
        token: String,
        peerId: Long,
        result: SendMessageResult,
    ) {
        val responseText = when {
            result.assistantMessage != null -> result.assistantMessage.content
            result.execution.status == AgentExecutionStatus.COMPLETED -> FALLBACK_ASSISTANT_REPLY
            result.execution.status == AgentExecutionStatus.RUNNING -> {
                sendReplySafely(bindingId, token, peerId, ACTIVE_EXECUTION_REPLY)
                return
            }
            else -> {
                sendReplySafely(bindingId, token, peerId, GENERIC_FAILURE_REPLY)
                return
            }
        }
        val chunks = vkTextChunks(
            text = responseText.ifBlank { FALLBACK_ASSISTANT_REPLY },
            maxLength = VK_TEXT_LIMIT,
        )
        chunks.forEach { chunk ->
            sendReplySafely(bindingId, token, peerId, chunk)
        }
    }

    private suspend fun handleApiError(
        binding: VkBotBinding,
        error: VkApiError,
    ) {
        when (error.errorCode) {
            // Both codes mean this token can never succeed on its own — an invalid/revoked token
            // (5) or one missing a required scope (15, e.g. before Long Poll access was granted).
            // Retrying without human intervention would just hammer VK forever, so disable.
            VK_ERROR_UNAUTHORIZED, VK_ERROR_ACCESS_DENIED ->
                repository.markError(binding.id, VK_UNAUTHORIZED, disable = true)
            VK_ERROR_TOO_MANY_REQUESTS, VK_ERROR_FLOOD_CONTROL, VK_ERROR_RATE_LIMIT_REACHED -> {
                repository.markError(binding.id, VK_RATE_LIMITED)
                // VK's rate-limit errors carry no retry_after; back off a fixed amount so this
                // binding doesn't immediately retry on the next ~1s poll tick and compound the
                // flood-control penalty it just hit.
                delay(RATE_LIMIT_BACKOFF_MS.milliseconds)
            }
            VK_ERROR_INTERNAL -> repository.markError(binding.id, VK_NETWORK_ERROR)
            else -> repository.markError(binding.id, VK_UNKNOWN_ERROR)
        }
    }

    /**
     * Sends the "typing" activity immediately, then repeats it every [TYPING_REPEAT_INTERVAL_MS]
     * — shorter than VK's own few-second expiry — so the indicator stays up continuously while the
     * agent turn is running. Launched concurrently with the turn (never awaited) so this
     * best-effort UI signal cannot delay starting the turn itself. [TYPING_MAX_DURATION_MS] is a
     * safety net in case the caller never cancels this job.
     */
    private suspend fun repeatTypingIndicator(bindingId: UUID, token: String, peerId: Long, groupId: Long) {
        withTimeoutOrNull(TYPING_MAX_DURATION_MS.milliseconds) {
            while (isActive) {
                setActivitySafely(bindingId, token, peerId, groupId)
                delay(TYPING_REPEAT_INTERVAL_MS.milliseconds)
            }
        }
    }

    private suspend fun setActivitySafely(bindingId: UUID, token: String, peerId: Long, groupId: Long) {
        if (!repository.hasActiveLease(bindingId, instanceId, clock.instant())) {
            return
        }
        try {
            botApi.setActivity(groupToken = token, peerId = peerId, groupId = groupId, type = "typing")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            logger.warn("VK typing indicator send failed for peer {}", peerId)
        }
    }

    /**
     * Foreign/wrong-account traffic on an already-linked or already-claimed binding gets this
     * reply once per [ALREADY_BOUND_REPLY_COOLDOWN_MS] window instead of once per message — an
     * unbound-rate reply to repeated foreign messages would itself risk tripping VK's own
     * flood-control on the community's token.
     */
    private suspend fun sendAlreadyBoundReplyThrottled(bindingId: UUID, token: String, peerId: Long) {
        val now = clock.instant()
        val last = lastAlreadyBoundReplyAt[bindingId]
        if (last != null && Duration.between(last, now).toMillis() < ALREADY_BOUND_REPLY_COOLDOWN_MS) {
            return
        }
        lastAlreadyBoundReplyAt[bindingId] = now
        sendReplySafely(bindingId, token, peerId, ALREADY_BOUND_REPLY)
    }

    private suspend fun sendReplySafely(
        bindingId: UUID,
        token: String,
        peerId: Long,
        text: String,
    ) {
        if (!repository.hasActiveLease(bindingId, instanceId, clock.instant())) {
            return
        }
        try {
            botApi.sendMessage(groupToken = token, peerId = peerId, text = text)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            logger.warn("VK reply send failed for peer {}", peerId)
        }
    }

    private companion object {
        const val LONGPOLL_WAIT_SECONDS: Int = 25
        const val MAX_LONGPOLL_ATTEMPTS: Int = 3
        const val POLL_LOOP_DELAY_MS: Long = 1_000L
        const val LEASE_TTL_SECONDS: Long = 45L
        const val TYPING_REPEAT_INTERVAL_MS: Long = 4_000L
        const val TYPING_MAX_DURATION_MS: Long = 5 * 60 * 1_000L
        const val DEFAULT_MAX_CONCURRENCY: Int = 4
        const val MAX_INCOMING_TEXT_LENGTH: Int = 8_000
        const val MESSAGE_NEW_EVENT: String = "message_new"
        const val LONGPOLL_RETRY_DELAY_MS: Long = 500L
        const val RATE_LIMIT_BACKOFF_MS: Long = 5_000L
        const val ALREADY_BOUND_REPLY_COOLDOWN_MS: Long = 60_000L

        const val VK_ERROR_UNAUTHORIZED: Int = 5
        const val VK_ERROR_TOO_MANY_REQUESTS: Int = 6
        const val VK_ERROR_INTERNAL: Int = 10
        const val VK_ERROR_FLOOD_CONTROL: Int = 9
        const val VK_ERROR_ACCESS_DENIED: Int = 15
        const val VK_ERROR_RATE_LIMIT_REACHED: Int = 29

        const val LINKED_REPLY: String = "Готово, этот VK-аккаунт привязан к чату Souz."
        const val FALLBACK_ASSISTANT_REPLY: String = "Готово."
        const val ACTIVE_EXECUTION_REPLY: String = "В этом чате уже выполняется задача. Попробуй позже."
        const val GENERIC_FAILURE_REPLY: String = "Не удалось выполнить команду."
        const val ALREADY_BOUND_REPLY: String = "Этот бот уже привязан к другому VK-аккаунту."
        const val PENDING_LINK_REPLY: String = "Чтобы привязать этот чат, отправь секрет, который показал Souz."
        const val TOO_LONG_REPLY: String = "Сообщение слишком длинное."

        const val VK_UNAUTHORIZED: String = "vk_unauthorized"
        const val VK_RATE_LIMITED: String = "vk_rate_limited"
        const val VK_NETWORK_ERROR: String = "vk_network_error"
        const val VK_UNKNOWN_ERROR: String = "vk_unknown_error"
        const val VK_TOKEN_DECRYPT_ERROR: String = "vk_token_decrypt_error"

        fun leaseRenewIntervalMs(leaseTtlSeconds: Long): Long =
            (leaseTtlSeconds * 1_000L / 3L).coerceAtLeast(1_000L)

        fun defaultInstanceId(): String {
            val host = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("unknown-host")
            return "$host:${UUID.randomUUID()}"
        }
    }
}
