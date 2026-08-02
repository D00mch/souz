package ru.souz.backend.client

import com.fasterxml.jackson.databind.JsonNode
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.backend.agent.runtime.BackendConversationRuntime

internal data class ClientToolOutcome(
    val status: String,
    val result: JsonNode?,
    val error: ClientError?,
)

internal data class PendingClientTool(
    val toolCallId: String,
    val result: CompletableDeferred<ClientToolOutcome> = CompletableDeferred(),
)

internal class ClientThreadRuntimeRegistry {
    private data class State(
        val mutex: Mutex = Mutex(),
        val runtimeReady: CompletableDeferred<BackendConversationRuntime> = CompletableDeferred(),
        var runtime: BackendConversationRuntime? = null,
        var latestDevice: ClientDevice,
        var pendingTool: PendingClientTool? = null,
        val pendingAcks: MutableMap<String, CompletableDeferred<Unit>> = linkedMapOf(),
    )

    private val states = ConcurrentHashMap<UUID, State>()

    fun contains(threadId: UUID): Boolean = states.containsKey(threadId)

    fun register(threadId: UUID, device: ClientDevice) {
        states.putIfAbsent(threadId, State(latestDevice = device))
    }

    suspend fun latestDevice(threadId: UUID): ClientDevice? =
        states[threadId]?.let { state -> state.mutex.withLock { state.latestDevice } }

    suspend fun attach(threadId: UUID, runtime: BackendConversationRuntime) {
        val state = states[threadId] ?: return
        state.mutex.withLock {
            state.runtime = runtime
            if (!state.runtimeReady.isCompleted) state.runtimeReady.complete(runtime)
        }
    }

    suspend fun detach(threadId: UUID, runtime: BackendConversationRuntime) {
        val state = states[threadId] ?: return
        state.mutex.withLock { if (state.runtime === runtime) state.runtime = null }
    }

    suspend fun <T> acceptInput(
        threadId: UUID,
        requestId: String,
        device: ClientDevice,
        input: String,
        canAccept: suspend () -> Boolean,
        commit: suspend () -> T,
    ): T? {
        val state = states[threadId] ?: return null
        val attached = state.mutex.withLock { state.runtime }
        if (attached == null) state.runtimeReady.await()
        return state.mutex.withLock {
            val runtime = state.runtime ?: return@withLock null
            if (!canAccept() || !runtime.submitToActiveRun(input)) return@withLock null
            val result = commit()
            state.latestDevice = device
            state.pendingAcks.putIfAbsent(requestId, CompletableDeferred())
            result
        }
    }

    suspend fun <T> withTerminalTransition(threadId: UUID, block: suspend () -> T): T {
        val state = states[threadId] ?: return block()
        return state.mutex.withLock { block() }
    }

    suspend fun <T> acceptCancellation(
        threadId: UUID,
        requestId: String,
        canAccept: suspend () -> Boolean,
        commit: suspend () -> T,
    ): T? {
        val state = states[threadId] ?: return null
        return state.mutex.withLock {
            if (!canAccept()) return@withLock null
            val result = commit()
            state.pendingAcks.putIfAbsent(requestId, CompletableDeferred())
            result
        }
    }

    suspend fun registerAck(threadId: UUID, requestId: String): Boolean {
        val state = states[threadId] ?: return false
        state.mutex.withLock { state.pendingAcks.putIfAbsent(requestId, CompletableDeferred()) }
        return true
    }

    suspend fun ackSent(threadId: UUID, requestId: String) {
        val state = states[threadId] ?: return
        state.mutex.withLock { state.pendingAcks.remove(requestId) }?.complete(Unit)
    }

    suspend fun awaitAcceptedInputAcks(threadId: UUID) {
        val state = states[threadId] ?: return
        while (true) {
            val pending = state.mutex.withLock { state.pendingAcks.values.toList() }
            if (pending.isEmpty()) return
            pending.forEach { it.await() }
        }
    }

    suspend fun beginTool(threadId: UUID, pending: PendingClientTool): Boolean {
        val state = states[threadId] ?: return false
        return state.mutex.withLock {
            if (state.pendingTool != null) false else {
                state.pendingTool = pending
                true
            }
        }
    }

    suspend fun finishTool(threadId: UUID, toolCallId: String, outcome: ClientToolOutcome): Boolean {
        val state = states[threadId] ?: return false
        val pending = state.mutex.withLock {
            state.pendingTool?.takeIf { it.toolCallId == toolCallId }?.also { state.pendingTool = null }
        } ?: return false
        return pending.result.complete(outcome)
    }

    suspend fun clearTool(threadId: UUID, toolCallId: String) {
        val state = states[threadId] ?: return
        state.mutex.withLock {
            if (state.pendingTool?.toolCallId == toolCallId) state.pendingTool = null
        }
    }
}
