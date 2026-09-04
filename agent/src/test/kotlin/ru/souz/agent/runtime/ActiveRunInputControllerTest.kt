package ru.souz.agent.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.Mutex
import ru.souz.agent.ActiveRunInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ActiveRunInputControllerTest {
    @Test
    fun `close winning the mailbox mutex rejects submission without enqueueing it`() = runTest {
        val mutex = Mutex(locked = true)
        val controller = ActiveRunInputController(mutex = mutex)
        val closing = async(start = CoroutineStart.UNDISPATCHED) { controller.close() }
        val submission = async(start = CoroutineStart.UNDISPATCHED) { controller.submit("too late") }

        mutex.unlock()

        closing.await()
        assertFalse(submission.await())
        assertFailsWith<CancellationException> { controller.drain() }
    }

    @Test
    fun `submission winning the mailbox mutex remains ordered before final sealing`() = runTest {
        val mutex = Mutex(locked = true)
        val controller = ActiveRunInputController(mutex = mutex)
        val submission = async(start = CoroutineStart.UNDISPATCHED) { controller.submit("accepted first") }
        val sealing = async(start = CoroutineStart.UNDISPATCHED) { controller.drainOrSeal() }

        mutex.unlock()

        assertTrue(submission.await())
        assertEquals("accepted first", sealing.await()?.single()?.input)
        assertNull(controller.drainOrSeal())
        assertFalse(controller.submit("after close"))
    }

    @Test
    fun `reserved submission is published only after callback succeeds`() = runTest {
        val controller = ActiveRunInputController()
        val callbackResult = CompletableDeferred<Boolean>()
        val submission = async {
            controller.submitAfter {
                ActiveRunInput(input = "durable input").takeIf { callbackResult.await() }
            }
        }
        runCurrent()

        val sealing = async { controller.drainOrSeal() }
        runCurrent()

        assertFalse(sealing.isCompleted)
        callbackResult.complete(true)

        assertTrue(submission.await())
        assertEquals("durable input", sealing.await()?.single()?.input)
    }

    @Test
    fun `reserved submission failure releases final sealing without enqueueing input`() = runTest {
        val controller = ActiveRunInputController()

        assertFalse(controller.submitAfter { null })
        assertNull(controller.drainOrSeal())
        assertFalse(controller.submit("after close"))
    }

    @Test
    fun `cancelled reserved submission releases final sealing without enqueueing input`() = runTest {
        val controller = ActiveRunInputController()
        val callbackResult = CompletableDeferred<Boolean>()
        val submission = async {
            controller.submitAfter {
                callbackResult.await()
                ActiveRunInput(input = "cancelled input")
            }
        }
        runCurrent()

        val sealing = async { controller.drainOrSeal() }
        runCurrent()

        assertFalse(sealing.isCompleted)
        submission.cancelAndJoin()

        assertNull(sealing.await())
    }

    @Test
    fun `committed reserved submission publishes input before propagating cancellation`() = runTest {
        val mutex = Mutex()
        val controller = ActiveRunInputController(mutex = mutex)
        val commitCompleted = CompletableDeferred<Unit>()
        val submission = async(start = CoroutineStart.UNDISPATCHED) {
            controller.submitAfter {
                commitCompleted.await()
                ActiveRunInput(input = "durable input")
            }
        }
        runCurrent()

        mutex.lock()
        commitCompleted.complete(Unit)
        runCurrent()
        submission.cancel()
        runCurrent()

        assertFalse(submission.isCompleted)
        mutex.unlock()
        submission.join()

        assertTrue(submission.isCancelled)
        assertEquals("durable input", controller.drain()?.single()?.input)
    }
}
