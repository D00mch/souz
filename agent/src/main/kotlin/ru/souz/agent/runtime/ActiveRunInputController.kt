package ru.souz.agent.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Process-local input queue owned by one Skills graph execution. */
internal class ActiveRunInputController {
    private val mutex = Mutex()
    private val accepting: CompletableJob = Job()
    private val queuedInputs = ArrayDeque<String>()
    private var activeLlmJob: Job? = null
    private var replannedLlmJob: Job? = null

    suspend fun submit(input: String): Boolean = mutex.withLock {
        if (!accepting.isActive) return false

        queuedInputs.addLast(input)
        activeLlmJob?.let { job ->
            replannedLlmJob = job
            job.cancel(ReplanLlmRequestCancellation())
        }
        true
    }

    suspend fun <T> runInterruptibleLlm(
        request: suspend () -> T,
    ): LlmRunResult<T> = supervisorScope {
        val requestJob = async(start = CoroutineStart.LAZY) { request() }
        val shouldStart = mutex.withLock {
            if (!accepting.isActive) {
                currentCoroutineContext().ensureActive()
                throw CancellationException("Active Skills graph run is closed")
            }
            if (queuedInputs.isNotEmpty()) {
                false
            } else {
                activeLlmJob = requestJob
                true
            }
        }

        if (!shouldStart) {
            requestJob.cancel()
            return@supervisorScope LlmRunResult.Replan
        }

        requestJob.start()
        try {
            LlmRunResult.Completed(requestJob.await())
        } catch (error: CancellationException) {
            currentCoroutineContext().ensureActive()
            val isIntentionalReplan = mutex.withLock {
                replannedLlmJob === requestJob && error.causedByReplanSignal()
            }
            if (isIntentionalReplan) LlmRunResult.Replan else throw error
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (activeLlmJob === requestJob) activeLlmJob = null
                    if (replannedLlmJob === requestJob) replannedLlmJob = null
                }
            }
        }
    }

    /** Drains the queue at a continuation boundary while keeping the run open. */
    suspend fun drain(): String? = mutex.withLock { drainLocked() }

    /** Drains pending input or seals the run when the queue is empty. */
    suspend fun drainOrSeal(): String? = mutex.withLock {
        drainLocked() ?: run {
            accepting.complete()
            null
        }
    }

    /** Stops accepting submissions. Safe to call from the existing non-suspending cancel path. */
    fun close() {
        accepting.complete()
    }

    private fun drainLocked(): String? {
        if (queuedInputs.isEmpty()) return null

        val messages = buildList {
            while (queuedInputs.isNotEmpty()) add(queuedInputs.removeFirst())
        }
        if (messages.size == 1) return messages.single()

        return buildString {
            append("<additional_user_messages>\n")
            messages.forEachIndexed { index, message ->
                append("<message index=\"")
                append(index + 1)
                append("\">\n")
                append(message)
                append("\n</message>\n")
            }
            append("</additional_user_messages>")
        }
    }

    private fun CancellationException.causedByReplanSignal(): Boolean =
        generateSequence<Throwable>(this) { it.cause }
            .any { it is ReplanLlmRequestCancellation }

    internal sealed interface LlmRunResult<out T> {
        data class Completed<T>(val value: T) : LlmRunResult<T>
        data object Replan : LlmRunResult<Nothing>
    }

    private class ReplanLlmRequestCancellation : CancellationException("Replan for queued user input")
}
