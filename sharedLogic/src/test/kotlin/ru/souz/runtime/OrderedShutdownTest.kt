package ru.souz.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.time.Duration.Companion.milliseconds

class OrderedShutdownTest {
    @Test
    fun `before shutdown failure completes concurrent and repeated callers after closing steps`() = runTest {
        val beforeFailure = IllegalStateException("before failed")
        val stepFailure = IllegalArgumentException("step failed")
        val stepStarted = CompletableDeferred<Unit>()
        val allowStep = CompletableDeferred<Unit>()
        val closed = mutableListOf<String>()
        val shutdown = OrderedShutdown(
            beforeShutdown = { throw beforeFailure },
            steps = listOf(
                shutdownStep("wait") {
                    stepStarted.complete(Unit)
                    allowStep.await()
                    closed += "wait"
                },
                shutdownStep("fail") {
                    closed += "fail"
                    throw stepFailure
                },
                shutdownStep("after") {
                    closed += "after"
                },
            ),
        )

        val firstCaller = async { runCatching { shutdown.shutdown() }.exceptionOrNull() }
        stepStarted.await()
        val secondCaller = async { runCatching { shutdown.shutdown() }.exceptionOrNull() }
        yield()
        allowStep.complete(Unit)

        val observed = withTimeout(5_000.milliseconds) {
            listOf(firstCaller.await(), secondCaller.await(), runCatching { shutdown.shutdown() }.exceptionOrNull())
        }

        observed.forEach { assertSame(beforeFailure, it) }
        assertEquals(listOf("step failed"), beforeFailure.suppressed.map { it.message })
        assertEquals(listOf("wait", "fail", "after"), closed)
    }

    @Test
    fun `step failure observer failure completes concurrent and repeated callers after remaining steps`() = runTest {
        val stepFailure = IllegalStateException("step failed")
        val observerFailure = IllegalArgumentException("observer failed")
        val laterFailure = UnsupportedOperationException("later failed")
        val stepStarted = CompletableDeferred<Unit>()
        val allowStep = CompletableDeferred<Unit>()
        val closed = mutableListOf<String>()
        val observedFailures = mutableListOf<String>()
        val shutdown = OrderedShutdown(
            steps = listOf(
                shutdownStep("wait") {
                    stepStarted.complete(Unit)
                    allowStep.await()
                    closed += "wait"
                },
                shutdownStep("fail") {
                    closed += "fail"
                    throw stepFailure
                },
                shutdownStep("later") {
                    closed += "later"
                    throw laterFailure
                },
                shutdownStep("after") {
                    closed += "after"
                },
            ),
            onStepFailure = { step, _ ->
                observedFailures += step.name
                if (step.name == "fail") {
                    throw observerFailure
                }
            },
        )

        val firstCaller = async { runCatching { shutdown.shutdown() }.exceptionOrNull() }
        stepStarted.await()
        val secondCaller = async { runCatching { shutdown.shutdown() }.exceptionOrNull() }
        yield()
        allowStep.complete(Unit)

        val observed = withTimeout(5_000.milliseconds) {
            listOf(firstCaller.await(), secondCaller.await(), runCatching { shutdown.shutdown() }.exceptionOrNull())
        }

        observed.forEach { assertSame(stepFailure, it) }
        assertEquals(listOf("observer failed", "later failed"), stepFailure.suppressed.map { it.message })
        assertEquals(listOf("wait", "fail", "later", "after"), closed)
        assertEquals(listOf("fail", "later"), observedFailures)
    }

    @Test
    fun `caller cancellation cannot interrupt owned shutdown or rerun steps`() = runTest {
        val stepStarted = CompletableDeferred<Unit>()
        val allowStep = CompletableDeferred<Unit>()
        var closeCount = 0
        val shutdown = OrderedShutdown(
            steps = listOf(
                shutdownStep("wait") {
                    stepStarted.complete(Unit)
                    allowStep.await()
                    closeCount += 1
                },
            ),
        )

        val owner = async { shutdown.shutdown() }
        stepStarted.await()
        owner.cancel()
        allowStep.complete(Unit)
        owner.cancelAndJoin()

        withTimeout(5_000.milliseconds) { shutdown.shutdown() }
        assertEquals(1, closeCount)
    }
}
