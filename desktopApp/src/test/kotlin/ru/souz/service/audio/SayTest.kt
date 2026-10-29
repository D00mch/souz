package ru.souz.service.audio

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SayTest {
    @Test
    fun `cancelling queued speech preserves active playback and cancels only its own process`() = runTest {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { voiceDispatcher ->
            val acceptedStarted = CompletableDeferred<Unit>()
            val acceptedFinished = CountDownLatch(1)
            val recoveryStarted = CompletableDeferred<Unit>()
            val recoveryFinished = CountDownLatch(1)
            val clearStarted = CompletableDeferred<Unit>()
            val clearFinished = CountDownLatch(1)
            val acceptedProcess = speechProcess(acceptedStarted, acceptedFinished)
            val recoveryProcess = speechProcess(recoveryStarted, recoveryFinished)
            val clearProcess = speechProcess(clearStarted, clearFinished)
            val player = Say(StandardTestDispatcher(testScheduler), voiceDispatcher) { text, _ ->
                when (text) {
                    "accepted" -> acceptedProcess
                    "recovered" -> recoveryProcess
                    "clear" -> clearProcess
                    else -> error("Cancelled queued speech must never start: $text")
                }
            }
            try {
                val accepted = player.queue("accepted", 120)
                val failed = player.queue("failed", 120)
                val recovered = player.queue("recovered", 120)
                runCurrent()
                acceptedStarted.await()
                failed.cancel()
                runCurrent()
                assertFalse(accepted.isCancelled)
                assertTrue(player.isSpeaking.value)
                verify(exactly = 0) { acceptedProcess.destroyForcibly() }
                assertFalse(recoveryStarted.isCompleted)

                acceptedFinished.countDown()
                accepted.join()
                failed.join()
                recoveryStarted.await()
                recovered.cancelAndJoin()
                runCurrent()
                verify(exactly = 1) { acceptedProcess.destroyForcibly() }
                verify(exactly = 1) { recoveryProcess.destroyForcibly() }
                assertFalse(player.isSpeaking.value)

                val clearing = player.queue("clear", 120)
                val pending = player.queue("failed", 120)
                runCurrent()
                clearStarted.await()
                player.clearQueue()
                runCurrent()
                clearing.join()
                pending.join()
                runCurrent()
                assertTrue(clearing.isCancelled)
                assertTrue(pending.isCancelled)
                verify(exactly = 1) { clearProcess.destroyForcibly() }
                assertFalse(player.isSpeaking.value)
            } finally {
                acceptedFinished.countDown()
                recoveryFinished.countDown()
                clearFinished.countDown()
                player.clearQueue()
                runCurrent()
            }
        }
    }

    private fun speechProcess(started: CompletableDeferred<Unit>, finished: CountDownLatch): Process = mockk {
        every { waitFor() } answers {
            started.complete(Unit)
            finished.await()
            0
        }
        every { destroyForcibly() } answers { this@mockk }
    }
}
