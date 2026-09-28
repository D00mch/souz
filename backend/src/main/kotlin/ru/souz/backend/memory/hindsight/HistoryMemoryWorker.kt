package ru.souz.backend.memory.hindsight

import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import ru.souz.backend.storage.postgres.PostgresHistoryMemoryRepository

/** Permanent retain rejections stop after this many attempts. */
internal const val HISTORY_MEMORY_MAX_ATTEMPTS = 12

/** Transient retain failures are retried until this long after the fragment was created. */
internal const val HISTORY_MEMORY_TRANSIENT_BUDGET_HOURS = 24

internal class HistoryMemoryWorker(
    private val repository: PostgresHistoryMemoryRepository,
    private val memory: HindsightConversationMemoryRuntime,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(HistoryMemoryWorker::class.java)

    fun start(scope: CoroutineScope) = scope.launch {
        while (isActive) {
            try {
                if (processNext()) continue
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logger.warn("History memory poll failed category={}", error.javaClass.simpleName)
            }
            delay(5_000)
        }
    }

    internal suspend fun processNext(): Boolean {
        val fragment = repository.claim() ?: return false
        try {
            coroutineScope {
                val heartbeat = launch {
                    while (isActive) {
                        delay(30_000)
                        check(repository.renew(fragment)) { "History memory lease lost" }
                    }
                }
                try {
                    val documents = repository.documents(fragment)
                    if (documents.isNotEmpty()) memory.captureHistory(fragment.userId, fragment.chatId, documents)
                    check(repository.complete(fragment)) { "History memory lease lost" }
                    logger.info("History memory captured chatId={} fragmentId={} attempt={} documents={}",
                        fragment.chatId, fragment.id, fragment.attempts, documents.size)
                } finally {
                    heartbeat.cancelAndJoin()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val category = if (error is HindsightHttpFailure) "http_${error.statusCode}" else error.javaClass.simpleName
            val exhausted = if (error.isPermanentRetainFailure()) {
                "attempts".takeIf { fragment.attempts >= HISTORY_MEMORY_MAX_ATTEMPTS }
            } else {
                val budgetEnd = fragment.createdAt.plus(Duration.ofHours(HISTORY_MEMORY_TRANSIENT_BUDGET_HOURS.toLong()))
                "time budget".takeUnless { clock.instant().isBefore(budgetEnd) }
            }
            when {
                exhausted == null -> {
                    repository.retry(fragment)
                    logger.warn("History memory retry chatId={} fragmentId={} attempt={} category={}",
                        fragment.chatId, fragment.id, fragment.attempts, category)
                }
                repository.fail(fragment) -> logger.error(
                    "History memory failed chatId={} fragmentId={} attempts={} reason={} category={}",
                    fragment.chatId, fragment.id, fragment.attempts, exhausted, category,
                )
                else -> logger.warn("History memory lease lost chatId={} fragmentId={} attempt={} category={}",
                    fragment.chatId, fragment.id, fragment.attempts, category)
            }
        }
        return true
    }
}

/** HTTP 4xx other than 408 and 429, and `success: false`, fail the same way on retry. */
private fun Exception.isPermanentRetainFailure(): Boolean = when (this) {
    is HindsightRetainRejected -> true
    is HindsightHttpFailure -> statusCode in 400..499 && statusCode != 408 && statusCode != 429
    else -> false
}
