package ru.souz.backend.llm

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-wide record of failing models. After [threshold] consecutive failures a model is skipped for
 * [cooldown]; the first call after that is its single trial while other calls keep skipping it.
 */
internal class ModelFailureMemory(
    private val threshold: Int = 3,
    private val cooldown: Duration = 5.minutes,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val mutex = Mutex()
    private val failures = mutableMapOf<Any, Failures>()

    suspend fun shouldTry(key: Any): Boolean = mutex.withLock {
        val entry = failures[key]
        when {
            entry == null || entry.count < threshold -> true
            entry.retryAt.hasNotPassedNow() -> false
            else -> {
                entry.retryAt = timeSource.markNow() + cooldown
                true
            }
        }
    }

    suspend fun record(key: Any, failed: Boolean): Unit = mutex.withLock {
        if (failed) {
            val entry = failures.getOrPut(key) { Failures(retryAt = timeSource.markNow()) }
            entry.count += 1
            if (entry.count >= threshold) entry.retryAt = timeSource.markNow() + cooldown
        } else {
            failures.remove(key)
        }
    }

    private class Failures(var retryAt: TimeMark, var count: Int = 0)
}
