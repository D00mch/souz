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
    private val requestsByUser = LinkedHashMap<String, List<Instant>>()
    private val tokenUsageByUser = LinkedHashMap<String, List<TokenSpend>>()
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
            val cutoff = now().minus(Duration.ofMinutes(1))
            val queue = requestsByUser[userId].orEmpty().dropWhile { it.isBefore(cutoff) }
            requestsByUser[userId] = queue
            if (queue.size >= limits.perUserRequestsPerMinute) {
                throw QuotaExceededException(
                    code = "per_user_requests_per_minute_exceeded",
                    message = "Per-user request rate limit exceeded.",
                )
            }
            requestsByUser[userId] = queue + now()
        }
    }

    suspend fun checkTokenQuota(userId: String) {
        mutex.withLock {
            val cutoff = now().minus(Duration.ofMinutes(1))
            val queue = tokenUsageByUser[userId].orEmpty().dropWhile { it.at.isBefore(cutoff) }
            tokenUsageByUser[userId] = queue
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
            val cutoff = now().minus(Duration.ofMinutes(1))
            val queue = tokenUsageByUser[userId].orEmpty().dropWhile { it.at.isBefore(cutoff) }
            tokenUsageByUser[userId] = queue + TokenSpend(at = now(), totalTokens = totalTokens)
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

    private data class TokenSpend(
        val at: Instant,
        val totalTokens: Int,
    )
}
