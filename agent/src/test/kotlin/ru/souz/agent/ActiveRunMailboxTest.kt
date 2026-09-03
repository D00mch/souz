package ru.souz.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ActiveRunMailboxTest {
    @Test
    fun `closed mailbox rejects submission without enqueueing it`() = runTest {
        val mailbox = ActiveRunMailbox { emptyList() }

        mailbox.close()

        assertFalse(mailbox.submit { ActiveRunInput(input = "too late") })
        assertFailsWith<CancellationException> { mailbox.drain() }
    }

    @Test
    fun `reserved submission remains ordered before final sealing`() = runTest {
        val mailbox = ActiveRunMailbox { emptyList() }
        val releaseProducer = CompletableDeferred<Unit>()
        val submission = async {
            mailbox.submit {
                releaseProducer.await()
                ActiveRunInput(input = "accepted first")
            }
        }
        runCurrent()
        val sealing = async { mailbox.drainOrSeal() }
        runCurrent()
        releaseProducer.complete(Unit)

        assertTrue(submission.await())
        assertEquals("accepted first", sealing.await()?.single()?.input)
        assertNull(mailbox.drainOrSeal())
        assertFalse(mailbox.submit { ActiveRunInput(input = "after close") })
    }

    @Test
    fun `reserved submission is published only after producer succeeds`() = runTest {
        val mailbox = ActiveRunMailbox { emptyList() }
        val producedInput = CompletableDeferred<ActiveRunInput?>()
        val submission = async { mailbox.submit { producedInput.await() } }
        runCurrent()

        val sealing = async { mailbox.drainOrSeal() }
        runCurrent()

        assertFalse(sealing.isCompleted)
        producedInput.complete(ActiveRunInput(input = "durable input"))

        assertTrue(submission.await())
        assertEquals("durable input", sealing.await()?.single()?.input)
    }

    @Test
    fun `producer returning null releases final sealing without enqueueing input`() = runTest {
        val mailbox = ActiveRunMailbox { emptyList() }

        assertFalse(mailbox.submit { null })
        assertNull(mailbox.drainOrSeal())
        assertFalse(mailbox.submit { ActiveRunInput(input = "after close") })
    }

    @Test
    fun `cancelled producer releases final sealing without enqueueing input`() = runTest {
        val mailbox = ActiveRunMailbox { emptyList() }
        val producedInput = CompletableDeferred<ActiveRunInput?>()
        val submission = async { mailbox.submit { producedInput.await() } }
        runCurrent()

        val sealing = async { mailbox.drainOrSeal() }
        runCurrent()

        assertFalse(sealing.isCompleted)
        submission.cancelAndJoin()

        assertNull(sealing.await())
    }

    @Test
    fun `committed reserved submission publishes input before propagating cancellation`() = runTest {
        val mailbox = ActiveRunMailbox { emptyList() }
        val submission = async {
            mailbox.submit {
                currentCoroutineContext().job.cancel()
                ActiveRunInput(input = "durable input")
            }
        }
        runCurrent()
        submission.join()

        assertTrue(submission.isCancelled)
        assertEquals("durable input", mailbox.drain()?.single()?.input)
    }

    @Test
    fun `history loading neither wakes the LLM request nor advances its stream revision`() = runTest {
        val history = listOf(message(LLMMessageRole.assistant, "already answered"))
        var pending = history
        val mailbox = ActiveRunMailbox {
            pending.also { pending = emptyList() }
        }
        val request = mailbox.nextLlmStep() as ActiveRunMailbox.NextLlmStep.Request

        assertEquals(history, mailbox.loadHistoryAtBoundary())

        assertFalse(request.inputAvailable.isCompleted)
        val unchanged = mailbox.nextLlmStep() as ActiveRunMailbox.NextLlmStep.Request
        assertEquals(0L, unchanged.streamRevision)
        assertTrue(mailbox.loadHistoryAtBoundary().isEmpty())
    }

    @Test
    fun `fixed history source is consulted at every boundary`() = runTest {
        var loads = 0
        var pending = listOf(message(LLMMessageRole.user, "pending"))
        val mailbox = ActiveRunMailbox {
            loads += 1
            pending.also { pending = emptyList() }
        }

        assertEquals(listOf("pending"), mailbox.loadHistoryAtBoundary().map { it.content })
        assertTrue(mailbox.loadHistoryAtBoundary().isEmpty())
        assertEquals(2, loads)
    }

    @Test
    fun `history staged after a boundary snapshot belongs to the next boundary`() = runTest {
        val firstLoadStarted = CompletableDeferred<Unit>()
        val releaseFirstLoad = CompletableDeferred<Unit>()
        var pending = listOf(message(LLMMessageRole.user, "first"))
        val mailbox = ActiveRunMailbox {
            val snapshot = pending.also { pending = emptyList() }
            firstLoadStarted.complete(Unit)
            releaseFirstLoad.await()
            snapshot
        }

        val firstBoundary = async { mailbox.loadHistoryAtBoundary() }
        firstLoadStarted.await()
        pending = listOf(message(LLMMessageRole.assistant, "second"))
        releaseFirstLoad.complete(Unit)

        assertEquals(listOf("first"), firstBoundary.await().map { it.content })
        assertEquals(listOf("second"), mailbox.loadHistoryAtBoundary().map { it.content })
    }

    @Test
    fun `failed fixed history source is retried at a later boundary`() = runTest {
        var attempts = 0
        val mailbox = ActiveRunMailbox {
            attempts += 1
            if (attempts == 1) error("temporarily unavailable")
            listOf(message(LLMMessageRole.user, "recovered"))
        }

        assertTrue(mailbox.loadHistoryAtBoundary().isEmpty())
        assertEquals(listOf("recovered"), mailbox.loadHistoryAtBoundary().map { it.content })
        assertEquals(2, attempts)
    }

    @Test
    fun `cancelled fixed history source propagates cancellation and can be retried`() = runTest {
        var attempts = 0
        val mailbox = ActiveRunMailbox {
            attempts += 1
            if (attempts == 1) throw CancellationException("boundary cancelled")
            listOf(message(LLMMessageRole.assistant, "recovered"))
        }

        assertFailsWith<CancellationException> { mailbox.loadHistoryAtBoundary() }
        assertEquals(listOf("recovered"), mailbox.loadHistoryAtBoundary().map { it.content })
        assertEquals(2, attempts)
    }

    @Test
    fun `final sealing does not invoke the history source`() = runTest {
        var loads = 0
        val mailbox = ActiveRunMailbox {
            loads += 1
            listOf(message(LLMMessageRole.user, "pending"))
        }

        assertNull(mailbox.drainOrSeal())
        assertEquals(0, loads)
        assertFalse(mailbox.submit { ActiveRunInput(input = "too late") })
        assertFailsWith<CancellationException> { mailbox.loadHistoryAtBoundary() }
    }

    @Test
    fun `structured submissions retain role preserving history and FIFO batches`() = runTest {
        val mailbox = ActiveRunMailbox { emptyList() }
        val first = ActiveRunInput(
            history = listOf(message(LLMMessageRole.assistant, "client answer")),
            input = "first execute",
        )
        val second = ActiveRunInput(
            history = listOf(message(LLMMessageRole.user, "client question")),
            input = "second execute",
        )

        assertTrue(mailbox.submit { first })
        assertTrue(mailbox.submit { second })

        assertEquals(listOf(first, second), mailbox.drain())
        val request = mailbox.nextLlmStep() as ActiveRunMailbox.NextLlmStep.Request
        assertEquals(2L, request.streamRevision)
    }

    @Test
    fun `structured submissions reject non conversational history roles`() {
        assertFailsWith<IllegalArgumentException> {
            ActiveRunInput(
                history = listOf(message(LLMMessageRole.function, "tool result")),
                input = "continue",
            )
        }
    }

    private fun message(role: LLMMessageRole, content: String) = LLMRequest.Message(role, content)
}
