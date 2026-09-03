package ru.souz.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import kotlin.coroutines.cancellation.CancellationException

/** Mutex-serialized input mailbox for one steerable graph execution. */
class ActiveRunMailbox internal constructor(
    private val loadPendingHistory: suspend () -> List<LLMRequest.Message>,
) {
    private val logger = LoggerFactory.getLogger(ActiveRunMailbox::class.java)
    private val mutex = Mutex()
    private var state: State = State.Open()

    /**
     * Reserves an open mailbox, runs [build], and publishes its result at one ordering point.
     * Final sealing waits for the reservation, allowing durable state to commit before input is visible.
     */
    suspend fun submit(build: suspend () -> ActiveRunInput?): Boolean {
        if (!reserveInput()) return false
        var released = false
        return try {
            val input = build()
            val published = withContext(NonCancellable) {
                releaseReservation(input).also { released = true }
            }
            currentCoroutineContext().ensureActive()
            published
        } finally {
            if (!released) withContext(NonCancellable) { releaseReservation(input = null) }
        }
    }

    /** Reads the fixed history source at a boundary without waking or reserving the run. */
    internal suspend fun loadHistoryAtBoundary(): List<LLMRequest.Message> {
        mutex.withLock { openState() }
        return try {
            loadPendingHistory().also(::requireSupportedHistory)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.warn("Active-run history could not be loaded; it remains pending at the source", error)
            emptyList()
        }
    }

    /** Returns queued input or the revision and notification for the next LLM attempt. */
    internal suspend fun nextLlmStep(): NextLlmStep = mutex.withLock {
        val open = openState()
        if (open.queuedInputs.isNotEmpty()) {
            NextLlmStep.QueuedInput(drainLocked(open))
        } else {
            NextLlmStep.Request(open.streamRevision, open.inputAvailable)
        }
    }

    /** Drains all input accepted before this operation, preserving FIFO message boundaries. */
    internal suspend fun drain(): List<ActiveRunInput>? = mutex.withLock {
        val open = openState()
        if (open.queuedInputs.isEmpty()) null else drainLocked(open)
    }

    /** Atomically drains pending input or closes an empty mailbox around a final response. */
    internal suspend fun drainOrSeal(): List<ActiveRunInput>? {
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
    internal suspend fun close() {
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
        ?: throw CancellationException("Active steerable graph run is closed")

    private fun drainLocked(open: State.Open): List<ActiveRunInput> {
        check(open.queuedInputs.isNotEmpty()) { "Queued input is required" }
        val messages = open.queuedInputs.toList()
        state = State.Open(
            streamRevision = open.streamRevision,
            pendingReservations = open.pendingReservations,
            reservationChanged = open.reservationChanged,
        )
        return messages
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

    private suspend fun releaseReservation(input: ActiveRunInput?): Boolean = mutex.withLock {
        val open = state as? State.Open ?: return false
        check(open.pendingReservations > 0) { "No pending input reservation to release" }
        state = open.copy(
            pendingReservations = open.pendingReservations - 1,
            reservationChanged = CompletableDeferred(),
        )
        val updated = state as State.Open
        if (input != null) enqueueLocked(updated, input)
        open.reservationChanged.complete(Unit)
        input != null
    }

    private fun enqueueLocked(open: State.Open, input: ActiveRunInput) {
        open.queuedInputs.addLast(input)
        state = open.copy(streamRevision = open.streamRevision + 1)
        open.inputAvailable.complete(Unit)
    }

    private fun requireSupportedHistory(messages: List<LLMRequest.Message>) {
        require(messages.all { it.role == LLMMessageRole.user || it.role == LLMMessageRole.assistant }) {
            "Active-run history supports only user and assistant messages"
        }
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
            val pendingReservations: Int = 0,
            val reservationChanged: CompletableDeferred<Unit> = CompletableDeferred(),
        ) : State

        data object Closed : State
    }
}
