package ru.souz.backend.llm.quota

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import ru.souz.backend.app.BackendLlmLimits
import ru.souz.llms.LlmProvider
import java.time.Instant
import kotlin.test.assertEquals

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ExecutionQuotaManagerTest {
    @Test
    fun `zero token chunks skip quota bookkeeping while positive usage expires normally`() = runTest {
        var clockReads = 0
        var time = Instant.parse("2026-11-05T12:00:00Z")
        val manager = ExecutionQuotaManager(
            limits = BackendLlmLimits(perUserTokensPerMinute = 10),
            now = { clockReads++; time },
        )

        repeat(10_000) { manager.recordTokenUsage("user-a", 0) }
        assertEquals(0, clockReads, "Empty stream chunks must not touch the quota queue or clock.")
        manager.recordTokenUsage("user-a", 10)
        assertSuspendFailsWith<QuotaExceededException> { manager.checkTokenQuota("user-a") }
        manager.checkTokenQuota("user-b")
        time = time.plusSeconds(61)
        manager.checkTokenQuota("user-a")
    }

    @Test
    fun `per user admission waits and cancelled waiters do not leak capacity`() = runTest {
        val manager = ExecutionQuotaManager(
            limits = BackendLlmLimits(
                perUserConcurrentExecutions = 1,
                perUserRequestsPerMinute = 10,
                perUserTokensPerMinute = 10_000,
                globalProviderConcurrency = 10,
            )
        )
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val first = async {
            manager.withExecutionPermit(userId = "user-a") {
                started.complete(Unit)
                gate.await()
            }
        }

        started.await()
        val cancelled = async { manager.withExecutionPermit("user-a") { error("Cancelled waiter entered") } }
        val waiting = async { manager.withExecutionPermit("user-a") { "next" } }
        runCurrent()
        assertFalse(waiting.isCompleted)
        cancelled.cancelAndJoin()
        manager.withExecutionPermit("user-b") { } // Other users remain independent.
        gate.complete(Unit)
        first.await()
        assertEquals("next", waiting.await())
        manager.withExecutionPermit("user-a") { }
    }

    @Test
    fun `per user requests per minute rejects excess calls`() = runTest {
        val manager = ExecutionQuotaManager(
            limits = BackendLlmLimits(
                perUserConcurrentExecutions = 2,
                perUserRequestsPerMinute = 1,
                perUserTokensPerMinute = 10_000,
                globalProviderConcurrency = 10,
            )
        )

        manager.checkRequestRate(userId = "user-a")
        assertSuspendFailsWith<QuotaExceededException> {
            manager.checkRequestRate(userId = "user-a")
        }
        manager.checkRequestRate(userId = "user-b")
    }

    @Test
    fun `per user token quota rejects once accumulated usage is exhausted`() = runTest {
        val manager = ExecutionQuotaManager(
            limits = BackendLlmLimits(
                perUserConcurrentExecutions = 2,
                perUserRequestsPerMinute = 10,
                perUserTokensPerMinute = 10,
                globalProviderConcurrency = 10,
            )
        )

        manager.checkTokenQuota(userId = "user-a")
        manager.recordTokenUsage(userId = "user-a", totalTokens = 7)
        manager.checkTokenQuota(userId = "user-a")
        manager.recordTokenUsage(userId = "user-a", totalTokens = 3)

        assertSuspendFailsWith<QuotaExceededException> {
            manager.checkTokenQuota(userId = "user-a")
        }
        manager.checkTokenQuota(userId = "user-b")
    }

    @Test
    fun `global provider concurrency cap is shared across users`() = runTest {
        val manager = ExecutionQuotaManager(
            limits = BackendLlmLimits(
                perUserConcurrentExecutions = 2,
                perUserRequestsPerMinute = 10,
                perUserTokensPerMinute = 10_000,
                globalProviderConcurrency = 1,
            )
        )
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val first = async {
            manager.withProviderPermit(provider = LlmProvider.OPENAI) {
                started.complete(Unit)
                gate.await()
            }
        }

        started.await()
        val cancelled = async { manager.withProviderPermit(LlmProvider.OPENAI) { error("Cancelled waiter entered") } }
        val waiting = async { manager.withProviderPermit(LlmProvider.OPENAI) { "next" } }
        runCurrent()
        assertFalse(waiting.isCompleted)
        cancelled.cancelAndJoin()
        manager.withProviderPermit(LlmProvider.ANTHROPIC) { } // Providers remain independent.
        gate.complete(Unit)
        first.await()
        assertEquals("next", waiting.await())
        manager.withProviderPermit(LlmProvider.OPENAI) { }
    }
}

private suspend inline fun <reified T : Throwable> assertSuspendFailsWith(
    noinline block: suspend () -> Unit,
): T =
    try {
        block()
        throw AssertionError("Expected exception ${T::class.simpleName}.")
    } catch (error: Throwable) {
        error as? T ?: throw AssertionError("Expected exception ${T::class.simpleName}, got ${error::class.simpleName}.", error)
    }
