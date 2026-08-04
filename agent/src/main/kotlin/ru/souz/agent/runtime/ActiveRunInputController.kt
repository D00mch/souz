package ru.souz.agent.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** Mutex-serialized mailbox for one Skills graph execution. */
internal class ActiveRunInputController(
    private val mutex: Mutex = Mutex(),
) {
    private var state: State = State.Open()

    /** Accepts [input] at one linearization point with closing and final sealing. */
    suspend fun submit(input: String): Boolean = mutex.withLock {
        val open = state as? State.Open ?: return false
        enqueueLocked(open, input)
        true
    }

    /**
     * Reserves an open mailbox, runs [beforePublish], and publishes [input] only if the
     * callback succeeds. Final sealing waits for outstanding reservations, so callers can
     * commit durable state before exposing the input to the running graph.
     */
    suspend fun submitAfter(input: String, beforePublish: suspend () -> Boolean): Boolean {
        if (!reserveInput()) return false
        var released = false
        return try {
            val shouldPublish = beforePublish()
            val published = withContext(NonCancellable) {
                releaseReservation(input.takeIf { shouldPublish }).also { released = true }
            }
            currentCoroutineContext().ensureActive()
            published
        } catch (error: Exception) {
            if (!released) withContext(NonCancellable) { releaseReservation(input = null) }
            throw error
        }
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
    suspend fun drainOrSeal(): String? {
        while (true) {
            val pendingReservation = mutex.withLock {
                val open = openState()
                when {
                    open.queuedInputs.isNotEmpty() -> return drainLocked(open)
                    open.pendingReservations > 0 -> open.reservationChanged
                    else -> {
                        closeLocked(open)
                        return null
                    }
                }
            }
            pendingReservation.await()
        }
    }

    /** Stops accepting submissions in the same state machine as enqueueing and draining. */
    suspend fun close() {
        while (true) {
            val pendingReservation = mutex.withLock {
                val open = state as? State.Open ?: return
                if (open.pendingReservations > 0) {
                    open.reservationChanged
                } else {
                    closeLocked(open)
                    return
                }
            }
            pendingReservation.await()
        }
    }

    private fun openState(): State.Open = state as? State.Open
        ?: throw CancellationException("Active Skills graph run is closed")

    private fun drainLocked(open: State.Open): String {
        check(open.queuedInputs.isNotEmpty()) { "Queued input is required" }
        val messages = open.queuedInputs.toList()
        state = State.Open(
            streamRevision = open.streamRevision,
            pendingReservations = open.pendingReservations,
            reservationChanged = open.reservationChanged,
        )
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
        open.reservationChanged.complete(Unit)
    }

    private suspend fun reserveInput(): Boolean = mutex.withLock {
        val open = state as? State.Open ?: return false
        state = open.copy(pendingReservations = open.pendingReservations + 1)
        true
    }

    private suspend fun releaseReservation(input: String?): Boolean = mutex.withLock {
        val open = state as? State.Open ?: return false
        check(open.pendingReservations > 0) { "No pending input reservation to release" }
        val pendingReservations = open.pendingReservations - 1
        state = open.copy(
            pendingReservations = pendingReservations,
            reservationChanged = CompletableDeferred(),
        )
        val updated = state as State.Open
        if (input != null) enqueueLocked(updated, input)
        open.reservationChanged.complete(Unit)
        input != null
    }

    private fun enqueueLocked(open: State.Open, input: String) {
        open.queuedInputs.addLast(input)
        state = open.copy(streamRevision = open.streamRevision + 1)
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
            val pendingReservations: Int = 0,
            val reservationChanged: CompletableDeferred<Unit> = CompletableDeferred(),
        ) : State

        data object Closed : State
    }
}
