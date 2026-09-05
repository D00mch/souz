package ru.souz.agent.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
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
    fun `pending submissions allow draining and LLM attempts across reservation groups`() = runTest {
        val controller = ActiveRunInputController()
        repeat(2) {
            val commit = CompletableDeferred<Unit>()
            val submission = async(start = CoroutineStart.UNDISPATCHED) {
                controller.submit {
                    commit.await()
                    ActiveRunInput(input = "committed")
                }
            }

            assertTrue(controller.submit("LLM boundary"))
            val step = controller.nextLlmStep() as ActiveRunInputController.NextLlmStep.QueuedInput
            assertEquals("LLM boundary", step.inputs.single().input)
            assertTrue(controller.submit("tool boundary"))
            assertEquals("tool boundary", controller.drain()?.single()?.input)
            assertTrue(controller.submit("final boundary"))
            assertEquals("final boundary", controller.drainOrSeal()?.single()?.input)

            val request = controller.nextLlmStep() as ActiveRunInputController.NextLlmStep.Request
            val sealing = async(start = CoroutineStart.UNDISPATCHED) { controller.drainOrSeal() }
            assertFalse(sealing.isCompleted)
            assertFalse(request.inputAvailable.isCompleted)
            commit.complete(Unit)
            assertTrue(submission.await())
            assertEquals("committed", sealing.await()?.single()?.input)
            assertTrue(request.inputAvailable.isCompleted)
        }
        assertNull(controller.drainOrSeal())
        assertFalse(controller.submit("after seal"))
    }

    @Test
    fun `final boundary observes published input while closure waits for remaining submissions`() = runTest {
        val controller = ActiveRunInputController()
        val commits = List(2) { CompletableDeferred<Unit>() }
        val submissions = commits.mapIndexed { index, commit ->
            async(start = CoroutineStart.UNDISPATCHED) {
                controller.submit {
                    commit.await()
                    ActiveRunInput(input = "input $index")
                }
            }
        }
        val sealing = async(start = CoroutineStart.UNDISPATCHED) { controller.drainOrSeal() }
        val closing = async(start = CoroutineStart.UNDISPATCHED) { controller.close() }
        commits[0].complete(Unit)
        assertTrue(submissions[0].await())
        runCurrent()

        assertTrue(sealing.isCompleted)
        assertEquals("input 0", sealing.await()?.single()?.input)
        assertFalse(closing.isCompleted)
        commits[1].complete(Unit)
        assertTrue(submissions[1].await())
        closing.await()
    }

    @Test
    fun `empty and cancelled submissions release final sealing without enqueueing input`() = runTest {
        for (cancelWhileWaiting in listOf(true, false)) {
            val controller = ActiveRunInputController()
            assertFalse(controller.submit { null })
            val callbackResult = CompletableDeferred<Unit>()
            val submission = async(start = CoroutineStart.UNDISPATCHED) {
                assertFailsWith<CancellationException> {
                    controller.submit {
                        callbackResult.await()
                        currentCoroutineContext().cancel()
                        null
                    }
                }
            }
            val sealing = async(start = CoroutineStart.UNDISPATCHED) { controller.drainOrSeal() }
            assertFalse(sealing.isCompleted)
            if (cancelWhileWaiting) {
                submission.cancel()
            } else {
                callbackResult.complete(Unit)
            }
            submission.join()
            assertNull(sealing.await())
        }
    }

    @Test
    fun `committed reserved submission publishes input before propagating cancellation`() = runTest {
        val mutex = Mutex()
        val controller = ActiveRunInputController(mutex = mutex)
        val commitCompleted = CompletableDeferred<Unit>()
        val submission = async(start = CoroutineStart.UNDISPATCHED) {
            controller.submit {
                withContext(NonCancellable) {
                    commitCompleted.await()
                    ActiveRunInput(input = "durable input")
                }
            }
        }
        submission.cancel()
        runCurrent()

        assertFalse(submission.isCompleted)
        mutex.lock()
        commitCompleted.complete(Unit)
        runCurrent()
        assertFalse(submission.isCompleted)
        mutex.unlock()
        submission.join()

        assertTrue(submission.isCancelled)
        assertEquals("durable input", controller.drain()?.single()?.input)
    }
}

private suspend fun ActiveRunInputController.submit(input: String): Boolean =
    submit { ActiveRunInput(input = input) }
