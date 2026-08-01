package ru.souz.agent.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.Mutex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        assertEquals("accepted first", sealing.await())
        assertNull(controller.drainOrSeal())
        assertFalse(controller.submit("after close"))
    }
}
