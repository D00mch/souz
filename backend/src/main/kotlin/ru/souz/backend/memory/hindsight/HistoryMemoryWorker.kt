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
            val permanent = error is HindsightRetainRejected ||
                (error is HindsightHttpFailure && error.statusCode in 400..499 && error.statusCode !in listOf(408, 429))
            val exhausted = if (permanent) fragment.attempts >= 12
                else !clock.instant().isBefore(fragment.createdAt.plus(Duration.ofHours(24)))
            val updated = if (exhausted) repository.complete(fragment, failed = true) else repository.retry(fragment)
            val category = if (error is HindsightHttpFailure) "http_${error.statusCode}" else error.javaClass.simpleName
            when {
                !updated -> logger.warn("History memory lease lost chatId={} fragmentId={} attempt={} category={}",
                    fragment.chatId, fragment.id, fragment.attempts, category)
                exhausted -> logger.error("History memory failed chatId={} fragmentId={} attempts={} reason={} category={}",
                    fragment.chatId, fragment.id, fragment.attempts, if (permanent) "attempts" else "time budget", category)
                else -> logger.warn("History memory retry chatId={} fragmentId={} attempt={} category={}",
                    fragment.chatId, fragment.id, fragment.attempts, category)
            }
        }
        return true
    }
}
