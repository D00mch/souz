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

/**
 * Coordinates queued user input for one Skills graph execution.
 *
 * The queue, active LLM job, and replan marker form one state machine guarded by [Mutex]. Its main
 * invariant is that an LLM request cannot start between checking the queue and registering the job
 * that a later [submit] must cancel. Provider requests and tool execution must remain outside the
 * critical sections.
 *
 * Inputs are retained in FIFO order. The controller remains open across replans and tool calls, then
 * [drainOrSeal] atomically chooses between another replan and final-response acceptance.
 */
internal class ActiveRunInputController {
    private val mutex = Mutex()
    // CompletableJob is a thread-safe gate that close() can complete without suspending.
    private val accepting: CompletableJob = Job()
    private val queuedInputs = ArrayDeque<String>()
    private var activeLlmJob: Job? = null
    // Identifies the child cancelled by submit(), so unrelated cancellation is never swallowed.
    private var replannedLlmJob: Job? = null

    /**
     * Enqueues [input] while the run is open.
     *
     * Returns `true` after accepting the input. If the main LLM child is registered, only that child
     * is cancelled; the graph remains active and drains the queued input before replanning.
     */
    suspend fun submit(input: String): Boolean = mutex.withLock {
        if (!accepting.isActive) return false

        queuedInputs.addLast(input)
        activeLlmJob?.let { job ->
            replannedLlmJob = job
            job.cancel(ReplanLlmRequestCancellation())
        }
        true
    }

    /**
     * Runs the provider request only after atomically checking the queue and registering its child job.
     *
     * If input is already queued, the unstarted child is cancelled and the queued batch is returned
     * for replanning. Once registered, any later submission can cancel exactly this child without
     * cancelling the graph.
     */
    suspend fun <T> runInterruptibleLlm(
        request: suspend () -> T,
    ): LlmRunResult<T> = supervisorScope {
        // Keep the provider dormant until the queue check and job registration form one boundary.
        val requestJob = async(start = CoroutineStart.LAZY) { request() }
        // Queued input wins before registration; after registration, submit() can cancel this exact job.
        mutex.withLock {
            if (!accepting.isActive) {
                currentCoroutineContext().ensureActive()
                throw CancellationException("Active Skills graph run is closed")
            }
            if (queuedInputs.isNotEmpty()) {
                // A lazy child must still be completed before supervisorScope can return.
                requestJob.cancel()
                return@supervisorScope LlmRunResult.Replan(drainLocked())
            }
            activeLlmJob = requestJob
        }

        requestJob.start()
        try {
            LlmRunResult.Completed(requestJob.await())
        } catch (error: CancellationException) {
            // Parent/owner cancellation must terminate the graph, never masquerade as a replan.
            currentCoroutineContext().ensureActive()
            mutex.withLock {
                if (replannedLlmJob === requestJob && error.causedByReplanSignal()) {
                    return@supervisorScope LlmRunResult.Replan(drainLocked())
                }
            }
            throw error
        } finally {
            // Cancellation must not leave a stale child registered for a later submission.
            withContext(NonCancellable) {
                mutex.withLock {
                    if (activeLlmJob === requestJob) activeLlmJob = null
                    if (replannedLlmJob === requestJob) replannedLlmJob = null
                }
            }
        }
    }

    /**
     * Drains all currently queued inputs in FIFO order while keeping the run open.
     *
     * A single input is returned unchanged. Multiple inputs are rendered as one user message with
     * explicit boundaries so they enter history together without losing their order.
     */
    suspend fun drain(): String? = mutex.withLock { drainOrNullLocked() }

    /**
     * Atomically drains pending input or seals an empty run against further submissions.
     *
     * Holding the same mutex as [submit] prevents input from being accepted in the gap between the
     * final empty-queue check and sealing.
     */
    suspend fun drainOrSeal(): String? = mutex.withLock {
        drainOrNullLocked() ?: run {
            accepting.complete()
            null
        }
    }

    /**
     * Stops accepting submissions without suspending.
     *
     * A concurrent [submit] that already observed the gate as active is ordered before this close;
     * later submissions are rejected. Final response acceptance uses [drainOrSeal] instead because
     * queue draining and closing must be one mutex-protected operation.
     */
    fun close() {
        accepting.complete()
    }

    private fun drainLocked(): String {
        check(queuedInputs.isNotEmpty()) { "A replan must have queued user input" }
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

    private fun drainOrNullLocked(): String? =
        if (queuedInputs.isEmpty()) null else drainLocked()

    private fun CancellationException.causedByReplanSignal(): Boolean =
        generateSequence<Throwable>(this) { it.cause }
            .any { it is ReplanLlmRequestCancellation }

    internal sealed interface LlmRunResult<out T> {
        data class Completed<T>(val value: T) : LlmRunResult<T>
        data class Replan(val queuedInput: String) : LlmRunResult<Nothing>
    }

    private class ReplanLlmRequestCancellation : CancellationException("Replan for queued user input")
}
