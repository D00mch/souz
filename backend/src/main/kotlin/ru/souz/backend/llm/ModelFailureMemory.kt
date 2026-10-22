package ru.souz.backend.llm

import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Process-wide record of failing models. After [threshold] consecutive failures a model is skipped for
 * [cooldown]; the first call after that is its single trial while other calls keep skipping it.
 */
internal class ModelFailureMemory(
    private val threshold: Int = 3,
    private val cooldown: Duration = 5.minutes,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val failures = ConcurrentHashMap<Any, Failures>()

    fun shouldTry(key: Any): Boolean {
        var allowed = true

        failures.computeIfPresent(key) { _, entry ->
            when {
                entry.count < threshold -> entry
                entry.retryAt.hasNotPassedNow() -> {
                    allowed = false
                    entry
                }
                else -> entry.copy(
                    retryAt = timeSource.markNow() + cooldown,
                )
            }
        }

        return allowed
    }

    fun record(key: Any, failed: Boolean) {
        if (!failed) {
            failures.remove(key)
            return
        }

        failures.compute(key) { _, previous ->
            val count = (previous?.count ?: 0) + 1

            Failures(
                count = count,
                retryAt = if (count >= threshold) {
                    timeSource.markNow() + cooldown
                } else {
                    previous?.retryAt ?: timeSource.markNow()
                },
            )
        }
    }

    private data class Failures(
        val retryAt: TimeMark,
        val count: Int = 0,
    )
}
