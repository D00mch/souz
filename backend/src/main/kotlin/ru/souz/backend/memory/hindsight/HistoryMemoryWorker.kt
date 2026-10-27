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
import ru.souz.backend.common.withBackendLogContext
import ru.souz.backend.storage.postgres.PostgresHistoryMemoryRepository

internal class HistoryMemoryWorker(
    private val repository: PostgresHistoryMemoryRepository,
    private val memory: HindsightConversationMemoryRuntime,
    private val clock: Clock = Clock.systemUTC(),
    private val pollIntervalMillis: Long = 5_000,
) {
    init {
        require(pollIntervalMillis > 0) { "History memory poll interval must be positive." }
    }

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
            delay(pollIntervalMillis)
        }
    }

    internal suspend fun processNext(): Boolean {
        val fragment = repository.claim() ?: return false
        withBackendLogContext("userId" to fragment.userId, "chatId" to fragment.chatId, "fragmentId" to fragment.id) {
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
                        logger.info("History memory captured attempt={} documents={}", fragment.attempts, documents.size)
                    } finally {
                        heartbeat.cancelAndJoin()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val permanent = error is HindsightRetainRejected ||
                    (error is HindsightHttpFailure && error.statusCode in 400..499 && when (error.statusCode) { 408, 429 -> false; else -> true })
                val exhausted = if (permanent) fragment.attempts >= 12
                    else !clock.instant().isBefore(fragment.createdAt.plus(Duration.ofHours(24)))
                val updated = if (exhausted) repository.complete(fragment, failed = true) else repository.retry(fragment)
                val category = error.memoryErrorCategory()
                when {
                    !updated -> logger.warn("History memory lease lost attempt={} category={}", fragment.attempts, category)
                    exhausted -> logger.error("History memory failed attempts={} reason={} category={}",
                        fragment.attempts, if (permanent) "attempts" else "time budget", category)
                    else -> logger.warn("History memory retry attempt={} category={}", fragment.attempts, category)
                }
            }
        }
        return true
    }
}
