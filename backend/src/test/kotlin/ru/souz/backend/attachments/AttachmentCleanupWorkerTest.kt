package ru.souz.backend.attachments

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.slf4j.LoggerFactory

@OptIn(ExperimentalCoroutinesApi::class)
class AttachmentCleanupWorkerTest {
    @Test
    fun `full successful batches drain immediately but partial empty and failed iterations wait`() = runTest {
        val storage = mockk<AttachmentStorage>()
        var calls = 0
        coEvery { storage.cleanup() } coAnswers {
            when (++calls) {
                1, 2 -> 100
                3 -> 99
                4 -> 0
                5 -> throw java.sql.SQLException("private database diagnostics")
                else -> 0
            }
        }
        val logger = LoggerFactory.getLogger(AttachmentCleanupWorker::class.java) as Logger
        val logs = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(logs)
        val worker = AttachmentCleanupWorker(storage).start(this)
        try {
            runCurrent()
            coVerify(exactly = 3) { storage.cleanup() }
            for (expectedCalls in 4..6) {
                advanceTimeBy(59_999)
                runCurrent()
                coVerify(exactly = expectedCalls - 1) { storage.cleanup() }
                advanceTimeBy(1)
                runCurrent()
                coVerify(exactly = expectedCalls) { storage.cleanup() }
            }
            val failure = logs.list.single()
            assertEquals("Attachment cleanup iteration failed: exceptionClass=java.sql.SQLException", failure.formattedMessage)
            assertNull(failure.throwableProxy)
        } finally {
            worker.cancel()
            worker.join()
            logger.detachAppender(logs)
            logs.stop()
        }
    }

    @Test
    fun `cancellation stops cleanup without retry`() = runTest {
        val storage = mockk<AttachmentStorage>()
        coEvery { storage.cleanup() } throws CancellationException("shutdown")
        val worker = AttachmentCleanupWorker(storage).start(this)
        runCurrent()
        assertTrue(worker.isCancelled)
        advanceTimeBy(60_000)
        runCurrent()
        coVerify(exactly = 1) { storage.cleanup() }
    }
}
