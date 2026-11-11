package ru.souz.backend.llm.quota

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
    private val admissionsByUser = LinkedHashMap<String, UserAdmission>()
    private val requestsByUser = LinkedHashMap<String, List<Instant>>()
    private val tokenUsageByUser = LinkedHashMap<String, List<TokenSpend>>()
    private val providerSemaphores = LlmProvider.entries.associateWith {
        Semaphore(permits = limits.globalProviderConcurrency)
    }
    // Bound retained image sources/projections across users and providers, including admission waits.
    private val imageProjections = Semaphore(2)

    internal suspend fun <T> withImageProjectionPermit(block: suspend () -> T): T =
        imageProjections.withPermit { block() }

    suspend fun <T> withExecutionPermit(
        userId: String,
        block: suspend () -> T,
    ): T {
        val admission = mutex.withLock {
            admissionsByUser.getOrPut(userId) {
                UserAdmission(Semaphore(limits.perUserConcurrentExecutions))
            }.also { it.callers++ }
        }
        try {
            return admission.semaphore.withPermit { block() }
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    // Waiters retain the same entry as active calls through cancellation cleanup.
                    if (--admission.callers == 0) admissionsByUser.remove(userId)
                }
            }
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
        if (totalTokens <= 0) return
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
        return providerSemaphores.getValue(provider).withPermit { block() }
    }

    private class UserAdmission(val semaphore: Semaphore, var callers: Int = 0)

    private data class TokenSpend(
        val at: Instant,
        val totalTokens: Int,
    )
}
