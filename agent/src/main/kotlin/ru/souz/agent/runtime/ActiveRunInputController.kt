package ru.souz.agent.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/** Mutex-serialized mailbox for one Skills graph execution. */
internal class ActiveRunInputController(
    private val mutex: Mutex = Mutex(),
) {
    private var state: State = State.Open()

    /** Accepts [input] at one linearization point with closing and final sealing. */
    suspend fun submit(input: String): Boolean = mutex.withLock {
        val open = state as? State.Open ?: return false
        open.queuedInputs.addLast(input)
        state = open.copy(streamRevision = open.streamRevision + 1)
        open.inputAvailable.complete(Unit)
        true
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
    suspend fun drain(): String? = mutex.withLock {
        val open = openState()
        if (open.queuedInputs.isEmpty()) null else drainLocked(open)
    }

    /** Atomically drains pending input or closes an empty mailbox around a final response. */
    suspend fun drainOrSeal(): String? = mutex.withLock {
        val open = openState()
        if (open.queuedInputs.isNotEmpty()) {
            drainLocked(open)
        } else {
            closeLocked(open)
            null
        }
    }

    /** Stops accepting submissions in the same state machine as enqueueing and draining. */
    suspend fun close() = mutex.withLock {
        (state as? State.Open)?.let(::closeLocked)
    }

    private fun openState(): State.Open = state as? State.Open
        ?: throw CancellationException("Active Skills graph run is closed")

    private fun drainLocked(open: State.Open): String {
        check(open.queuedInputs.isNotEmpty()) { "Queued input is required" }
        val messages = open.queuedInputs.toList()
        state = State.Open(streamRevision = open.streamRevision)
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

    private fun closeLocked(open: State.Open) {
        state = State.Closed
        open.inputAvailable.complete(Unit)
    }

    internal sealed interface NextLlmStep {
        data class QueuedInput(val input: String) : NextLlmStep

        data class Request(
            val streamRevision: Long,
            val inputAvailable: Deferred<Unit>,
        ) : NextLlmStep
    }

    private sealed interface State {
        data class Open(
            val queuedInputs: ArrayDeque<String> = ArrayDeque(),
            val streamRevision: Long = 0L,
            val inputAvailable: CompletableDeferred<Unit> = CompletableDeferred(),
        ) : State

        data object Closed : State
    }
}
