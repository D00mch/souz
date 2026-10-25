package ru.souz.backend.llm.quota

import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import ru.souz.backend.app.BackendLlmLimits
import ru.souz.llms.LlmProvider

class ExecutionQuotaManagerTest {
    @Test
    fun `quota windows include the cutoff and release only expired usage for each user`() = runTest {
        var now = Instant.EPOCH
        val manager = ExecutionQuotaManager(
            limits = BackendLlmLimits(perUserRequestsPerMinute = 2, perUserTokensPerMinute = 10),
            now = { now },
        )
        manager.checkRequestRate("user-a")
        manager.recordTokenUsage("user-a", 7)
        now = now.plusSeconds(30)
        manager.checkRequestRate("user-a")
        manager.recordTokenUsage("user-a", 3)
        manager.checkRequestRate("user-b")
        manager.recordTokenUsage("user-b", 10)

        now = Instant.EPOCH.plusSeconds(60)
        assertSuspendFailsWith<QuotaExceededException> { manager.checkRequestRate("user-a") }
        assertSuspendFailsWith<QuotaExceededException> { manager.checkTokenQuota("user-a") }

        now = now.plusNanos(1)
        manager.checkRequestRate("user-a")
        manager.checkTokenQuota("user-a")
        manager.recordTokenUsage("user-a", 7)
        assertSuspendFailsWith<QuotaExceededException> { manager.checkRequestRate("user-a") }
        assertSuspendFailsWith<QuotaExceededException> { manager.checkTokenQuota("user-a") }
        assertSuspendFailsWith<QuotaExceededException> { manager.checkTokenQuota("user-b") }

        now = Instant.EPOCH.plusSeconds(90).plusNanos(1)
        manager.checkRequestRate("user-a")
        manager.checkTokenQuota("user-a")
        manager.checkTokenQuota("user-b")
    }

    @Test
    fun `per user concurrent execution limit rejects second active execution`() = runTest {
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
        assertSuspendFailsWith<QuotaExceededException> {
            manager.withExecutionPermit(userId = "user-a") { }
        }

        gate.complete(Unit)
        first.await()
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
        assertSuspendFailsWith<QuotaExceededException> {
            manager.withProviderPermit(provider = LlmProvider.OPENAI) { }
        }

        gate.complete(Unit)
        first.await()
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
