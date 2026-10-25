package ru.souz.backend.attachments

import io.mockk.coEvery
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class AttachmentCleanupWorkerTest {
    @Test
    fun `waits after every pass or failure and stops on cancellation`() = runTest {
        val storage = mockk<AttachmentStorage>()
        var calls = 0
        coEvery { storage.cleanup() } coAnswers {
            when (++calls) {
                1 -> 100
                2 -> throw java.sql.SQLException("private diagnostics")
                else -> throw CancellationException("shutdown")
            }
        }
        val worker = AttachmentCleanupWorker(storage).start(backgroundScope)
        runCurrent()
        assertEquals(1, calls)
        for (expected in 2..3) {
            advanceTimeBy(59_999)
            runCurrent()
            assertEquals(expected - 1, calls)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(expected, calls)
        }
        assertTrue(worker.isCancelled)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(3, calls)
    }
}
