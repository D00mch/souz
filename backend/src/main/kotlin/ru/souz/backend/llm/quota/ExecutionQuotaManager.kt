package ru.souz.backend.llm.quota

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import ru.souz.backend.app.BackendLlmLimits
import ru.souz.llms.LlmProvider

class QuotaExceededException(
    val code: String,
    override val message: String,
) : RuntimeException(message)

class ExecutionQuotaManager(
    private val limits: BackendLlmLimits,
    private val now: () -> Instant = Instant::now,
) {
    private val mutex = Mutex()
    private val activeExecutionsByUser = LinkedHashMap<String, Int>()
    private val requestsByUser = LinkedHashMap<String, ArrayDeque<Instant>>()
    private val tokenUsageByUser = LinkedHashMap<String, ArrayDeque<TokenSpend>>()
    private val providerSemaphores = LlmProvider.entries.associateWith {
        Semaphore(permits = limits.globalProviderConcurrency)
    }

    suspend fun <T> withExecutionPermit(
        userId: String,
        block: suspend () -> T,
    ): T {
        acquireExecution(userId)
        try {
            return block()
        } finally {
            releaseExecution(userId)
        }
    }

    suspend fun checkRequestRate(userId: String) {
        mutex.withLock {
            val queue = requestsByUser.getOrPut(userId) { ArrayDeque() }
            pruneRequests(queue)
            if (queue.size >= limits.perUserRequestsPerMinute) {
                throw QuotaExceededException(
                    code = "per_user_requests_per_minute_exceeded",
                    message = "Per-user request rate limit exceeded.",
                )
            }
            queue.addLast(now())
        }
    }

    suspend fun checkTokenQuota(userId: String) {
        mutex.withLock {
            val queue = tokenUsageByUser.getOrPut(userId) { ArrayDeque() }
            pruneTokens(queue)
            if (queue.sumOf { it.totalTokens } >= limits.perUserTokensPerMinute) {
                throw QuotaExceededException(
                    code = "per_user_token_quota_exceeded",
                    message = "Per-user token quota exceeded.",
                )
            }
        }
    }

    suspend fun recordTokenUsage(
        userId: String,
        totalTokens: Int,
    ) {
        mutex.withLock {
            val queue = tokenUsageByUser.getOrPut(userId) { ArrayDeque() }
            pruneTokens(queue)
            queue.addLast(TokenSpend(at = now(), totalTokens = totalTokens))
        }
    }

    suspend fun <T> withProviderPermit(
        provider: LlmProvider,
        block: suspend () -> T,
    ): T {
        val semaphore = providerSemaphores.getValue(provider)
        if (!semaphore.tryAcquire()) {
            throw QuotaExceededException(
                code = "global_provider_concurrency_exceeded",
                message = "Global provider concurrency limit exceeded.",
            )
        }
        try {
            return block()
        } finally {
            semaphore.release()
        }
    }

    private suspend fun acquireExecution(userId: String) {
        mutex.withLock {
            val active = activeExecutionsByUser[userId] ?: 0
            if (active >= limits.perUserConcurrentExecutions) {
                throw QuotaExceededException(
                    code = "per_user_concurrent_executions_exceeded",
                    message = "Per-user concurrent execution limit exceeded.",
                )
            }
            activeExecutionsByUser[userId] = active + 1
        }
    }

    private suspend fun releaseExecution(userId: String) {
        mutex.withLock {
            val active = activeExecutionsByUser[userId] ?: return@withLock
            if (active <= 1) {
                activeExecutionsByUser.remove(userId)
            } else {
                activeExecutionsByUser[userId] = active - 1
            }
        }
    }

    private fun pruneRequests(queue: ArrayDeque<Instant>) {
        val cutoff = now().minus(Duration.ofMinutes(1))
        while (queue.firstOrNull()?.isBefore(cutoff) == true) {
            queue.removeFirst()
        }
    }

    private fun pruneTokens(queue: ArrayDeque<TokenSpend>) {
        val cutoff = now().minus(Duration.ofMinutes(1))
        while (queue.firstOrNull()?.at?.isBefore(cutoff) == true) {
            queue.removeFirst()
        }
    }

    private data class TokenSpend(
        val at: Instant,
        val totalTokens: Int,
    )
}
