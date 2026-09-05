package ru.souz.agent.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.souz.agent.ActiveRunInput
import kotlin.coroutines.cancellation.CancellationException

/** Mutex-serialized mailbox for one steerable graph execution. */
internal class ActiveRunInputController(
    private val mutex: Mutex = Mutex(),
) {
    private var state: State = State.Open()
    private var pendingSubmissions = 0
    private var submissionsFinished: CompletableDeferred<Unit>? = null

    /**
     * Reserves an open mailbox while [build] runs outside its mutex, then publishes atomically.
     * Closure waits for all submissions; draining can observe each input as soon as it is published.
     */
    suspend fun submit(build: suspend () -> ActiveRunInput?): Boolean {
        mutex.withLock {
            if (state !is State.Open) return false
            if (pendingSubmissions++ == 0) submissionsFinished = CompletableDeferred()
        }
        var input: ActiveRunInput? = null
        try {
            input = build()
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    input?.let { enqueueLocked(openState(), it) }
                    if (--pendingSubmissions == 0) {
                        submissionsFinished?.complete(Unit)
                        submissionsFinished = null
                    }
                }
            }
        }
        currentCoroutineContext().ensureActive()
        return input != null
    }

    /** Returns queued input or the revision and notification for the next LLM attempt. */
    suspend fun nextLlmStep(): NextLlmStep = mutex.withLock {
        val open = openState()
        if (open.queuedInputs.isNotEmpty()) {
            NextLlmStep.QueuedInput(drainLocked(open))
        } else {
            NextLlmStep.Request(open.streamRevision, open.inputAvailable)
        }
    }

    /** Drains all input accepted before this operation, preserving FIFO message boundaries. */
    suspend fun drain(): List<ActiveRunInput>? = mutex.withLock {
        val open = openState()
        if (open.queuedInputs.isEmpty()) null else drainLocked(open)
    }

    /** Atomically drains pending input or closes an empty mailbox around a final response. */
    suspend fun drainOrSeal(): List<ActiveRunInput>? {
        while (true) {
            val (finished, inputAvailable) = mutex.withLock {
                val open = openState()
                if (open.queuedInputs.isNotEmpty()) return drainLocked(open)
                val finished = submissionsFinished ?: run {
                    closeLocked(open)
                    return null
                }
                finished to open.inputAvailable
            }
            select<Unit> {
                finished.onAwait { }
                inputAvailable.onAwait { }
            }
        }
    }

    /** Stops accepting submissions in the same state machine as enqueueing and draining. */
    suspend fun close() {
        while (true) {
            val pending = mutex.withLock {
                val open = state as? State.Open ?: return
                submissionsFinished ?: run {
                    closeLocked(open)
                    return
                }
            }
            pending.await()
        }
    }

    private fun openState(): State.Open = state as? State.Open
        ?: throw CancellationException("Active steerable graph run is closed")

    private fun drainLocked(open: State.Open): List<ActiveRunInput> {
        check(open.queuedInputs.isNotEmpty()) { "Queued input is required" }
        val messages = open.queuedInputs.toList()
        state = State.Open(streamRevision = open.streamRevision)
        return messages
    }

    private fun closeLocked(open: State.Open) {
        state = State.Closed
        open.inputAvailable.complete(Unit)
    }

    private fun enqueueLocked(open: State.Open, input: ActiveRunInput) {
        open.queuedInputs.addLast(input)
        state = open.copy(streamRevision = open.streamRevision + 1)
        open.inputAvailable.complete(Unit)
    }

    internal sealed interface NextLlmStep {
        data class QueuedInput(val inputs: List<ActiveRunInput>) : NextLlmStep

        data class Request(
            val streamRevision: Long,
            val inputAvailable: Deferred<Unit>,
        ) : NextLlmStep
    }

    private sealed interface State {
        data class Open(
            val queuedInputs: ArrayDeque<ActiveRunInput> = ArrayDeque(),
            val streamRevision: Long = 0L,
            val inputAvailable: CompletableDeferred<Unit> = CompletableDeferred(),
        ) : State

        data object Closed : State
    }
}
