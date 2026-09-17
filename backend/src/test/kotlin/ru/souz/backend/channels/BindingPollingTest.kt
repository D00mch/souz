package ru.souz.backend.channels

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

class BindingPollingTest {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Test
    fun `bindings retry independently and survive refresh failures`() = runTest {
        val idle = UUID.randomUUID()
        val busy = UUID.randomUUID()
        var attempts = 0
        var idleCancelled = false
        var refreshFails = false
        val polling = launch {
            pollBindings(100, logger) {
                if (refreshFails) error("database unavailable")
                mapOf(
                    idle to suspend { try { awaitCancellation() } finally { idleCancelled = true } },
                    busy to suspend { attempts++; error("retry this binding") },
                )
            }
        }
        runCurrent()
        assertEquals(1, attempts)
        refreshFails = true
        advanceTimeBy(300)
        runCurrent()
        assertEquals(4, attempts)
        assertFalse(idleCancelled)
        polling.cancelAndJoin()
        assertTrue(idleCancelled)
    }

    @Test
    fun `removed binding finishes cancellation before the same id restarts`() = runTest {
        val id = UUID.randomUUID()
        val cleanup = CompletableDeferred<Unit>()
        var enabled = true
        var starts = 0
        var cancellations = 0
        val polling = launch {
            pollBindings(100, logger) {
                if (!enabled) emptyMap() else mapOf(id to suspend {
                    starts++
                    try {
                        awaitCancellation()
                    } finally {
                        cancellations++
                        withContext(NonCancellable) { cleanup.await() }
                    }
                })
            }
        }
        runCurrent()
        enabled = false
        advanceTimeBy(100)
        runCurrent()
        assertEquals(1, cancellations)
        enabled = true
        advanceTimeBy(100)
        runCurrent()
        assertEquals(1, starts)
        cleanup.complete(Unit)
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(2, starts)
        polling.cancelAndJoin()
        assertEquals(2, cancellations)
    }
}
