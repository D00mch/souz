package ru.souz.backend.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest

class BackendRuntimeResourcesTest {
    @Test
    fun `concurrent callers await one shutdown and observe the same aggregate failure`() = runTest {
        val shutdownStarted = CompletableDeferred<Unit>()
        val allowShutdown = CompletableDeferred<Unit>()
        val firstFailure = IllegalStateException("provider close failed")
        val secondFailure = IllegalArgumentException("database close failed")
        val closed = mutableListOf<String>()
        val resources = BackendRuntimeResources(
            cancelAndJoinApplicationWork = {
                closed += "application-work"
                shutdownStarted.complete(Unit)
                allowShutdown.await()
            },
            closeProviderClients = {
                closed += "provider-http"
                throw firstFailure
            },
            closeLocalRuntime = { closed += "local-runtime" },
            closeDataSource = {
                closed += "database"
                throw secondFailure
            },
        )

        val firstCaller = async { runCatching { resources.shutdown() }.exceptionOrNull() }
        shutdownStarted.await()
        val secondCaller = async { runCatching { resources.shutdown() }.exceptionOrNull() }
        allowShutdown.complete(Unit)

        val firstObserved = firstCaller.await()
        val secondObserved = secondCaller.await()
        val repeatedObserved = runCatching { resources.shutdown() }.exceptionOrNull()

        listOf(firstObserved, secondObserved, repeatedObserved).forEach { observed ->
            assertTrue(observed is IllegalStateException)
            assertEquals("provider close failed", observed.message)
            assertEquals(listOf("database close failed"), observed.suppressed.map { it.message })
        }
        assertEquals(
            listOf("application-work", "provider-http", "local-runtime", "database"),
            closed,
        )
    }

    @Test
    fun `caller cancellation cannot interrupt owned shutdown`() = runTest {
        val shutdownStarted = CompletableDeferred<Unit>()
        val allowShutdown = CompletableDeferred<Unit>()
        var providerClosed = false
        val resources = BackendRuntimeResources(
            cancelAndJoinApplicationWork = {
                shutdownStarted.complete(Unit)
                allowShutdown.await()
            },
            closeProviderClients = { providerClosed = true },
        )

        val owner = async { resources.shutdown() }
        shutdownStarted.await()
        owner.cancel()
        allowShutdown.complete(Unit)
        owner.cancelAndJoin()

        resources.shutdown()
        assertTrue(providerClosed)
    }

    @Test
    fun `process shutdown stops HTTP before runtime and aggregates failures`() = runTest {
        val order = mutableListOf<String>()
        val httpFailure = IllegalStateException("http stop failed")
        val runtimeFailure = IllegalArgumentException("runtime stop failed")

        val thrown = assertFailsWith<IllegalStateException> {
            shutdownBackendProcess(
                stopHttpIntake = {
                    order += "http"
                    throw httpFailure
                },
                shutdownRuntime = {
                    order += "runtime"
                    throw runtimeFailure
                },
            )
        }

        assertSame(httpFailure, thrown)
        assertEquals(listOf(runtimeFailure), thrown.suppressed.toList())
        assertEquals(listOf("http", "runtime"), order)
    }
}
