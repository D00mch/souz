package ru.souz.backend.execution.service

import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.AgentExecutionStatus

class AgentExecutionLauncherTest {
    @Test
    fun `registered background execution can be cancelled through registry`() = runBlocking {
        launcherFixture().use { fixture ->
            val bodyStarted = CompletableDeferred<Unit>()
            val cancellationObserved = CompletableDeferred<Unit>()

            val job = fixture.launcher.launchRegistered(
                execution = fixture.execution,
                onCancelled = { cancellationObserved.complete(Unit) },
            ) {
                bodyStarted.complete(Unit)
                awaitCancellation()
            }

            assertTrue(fixture.registry.contains(fixture.execution.id))
            withTimeout(5_000) { bodyStarted.await() }
            assertTrue(fixture.launcher.cancel(fixture.execution.id))
            withTimeout(5_000) { cancellationObserved.await() }
            withTimeout(5_000) { job.join() }

            assertFalse(fixture.registry.contains(fixture.execution.id))
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `cancellation before body dispatch invokes cancellation callback without entering execution body`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        launcherFixture(scope = scope).use { fixture ->
            var bodyStarted = false
            val cancellationObserved = CompletableDeferred<Unit>()
            val job = fixture.launcher.launchRegistered(
                execution = fixture.execution,
                onCancelled = { cancellationObserved.complete(Unit) },
            ) {
                bodyStarted = true
            }

            assertTrue(fixture.launcher.cancel(fixture.execution.id))
            runCurrent()
            withTimeout(5_000) { job.join() }

            assertFalse(bodyStarted)
            assertTrue(cancellationObserved.isCompleted)
            assertFalse(fixture.registry.contains(fixture.execution.id))
        }
    }

    @Test
    fun `cancelled execution stays registered until cancellation callback finishes`() = runBlocking {
        launcherFixture().use { fixture ->
            val bodyStarted = CompletableDeferred<Unit>()
            val cancellationStarted = CompletableDeferred<Unit>()
            val allowCancellation = CompletableDeferred<Unit>()
            val job = fixture.launcher.launchRegistered(
                execution = fixture.execution,
                onCancelled = {
                    cancellationStarted.complete(Unit)
                    allowCancellation.await()
                },
            ) {
                bodyStarted.complete(Unit)
                awaitCancellation()
            }
            withTimeout(5_000) { bodyStarted.await() }

            assertTrue(fixture.launcher.cancel(fixture.execution.id))
            withTimeout(5_000) { cancellationStarted.await() }

            assertTrue(fixture.registry.contains(fixture.execution.id))
            assertFalse(job.isCompleted)

            allowCancellation.complete(Unit)
            withTimeout(5_000) { job.join() }

            assertFalse(fixture.registry.contains(fixture.execution.id))
        }
    }
}

private class LauncherFixture(
    val launcher: AgentExecutionLauncher,
    val registry: ActiveExecutionJobRegistry,
    val execution: AgentExecution,
    private val scope: CoroutineScope,
    private val dispatcher: java.io.Closeable?,
) : AutoCloseable {
    override fun close() {
        scope.cancel()
        dispatcher?.close()
    }
}

private fun launcherFixture(
    scope: CoroutineScope? = null,
): LauncherFixture {
    val dispatcher = if (scope == null) Executors.newFixedThreadPool(4).asCoroutineDispatcher() else null
    val executionScope = scope ?: CoroutineScope(SupervisorJob() + requireNotNull(dispatcher))
    val registry = ActiveExecutionJobRegistry()
    val execution = AgentExecution(
        id = UUID.randomUUID(),
        userId = "launcher-test-user",
        chatId = UUID.randomUUID(),
        userMessageId = null,
        assistantMessageId = null,
        status = AgentExecutionStatus.RUNNING,
        requestId = null,
        clientMessageId = null,
        model = null,
        provider = null,
        startedAt = Instant.parse("2026-08-13T00:00:01Z"),
        finishedAt = null,
        cancelRequested = false,
        errorCode = null,
        errorMessage = null,
        usage = null,
        metadata = emptyMap(),
    )
    return LauncherFixture(
        launcher = AgentExecutionLauncher(
            executionScope = executionScope,
            activeJobs = registry,
        ),
        registry = registry,
        execution = execution,
        scope = executionScope,
        dispatcher = dispatcher,
    )
}
