package ru.souz.backend.client

import com.fasterxml.jackson.databind.JsonNode
import java.util.UUID
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
        val runtimeReady: CompletableDeferred<Unit> = CompletableDeferred(),
        var runtime: BackendConversationRuntime? = null,
        var latestDevice: ClientDevice,
        var pendingTool: PendingClientTool? = null,
        val pendingAcks: MutableMap<String, CompletableDeferred<Unit>> = linkedMapOf(),
        var terminal: Boolean = false,
    )

    private val mutex = Mutex()
    private val states = linkedMapOf<UUID, State>()

    suspend fun contains(threadId: UUID): Boolean = mutex.withLock {
        states.containsKey(threadId)
    }

    suspend fun register(threadId: UUID, device: ClientDevice) {
        mutex.withLock {
            states.putIfAbsent(threadId, State(latestDevice = device))
        }
    }

    suspend fun discard(threadId: UUID) {
        val discarded = mutex.withLock { states.remove(threadId) } ?: return
        discarded.runtimeReady.complete(Unit)
        discarded.pendingAcks.values.forEach { it.complete(Unit) }
        discarded.pendingTool?.result?.cancel()
    }

    suspend fun latestDevice(threadId: UUID): ClientDevice? = mutex.withLock {
        states[threadId]?.latestDevice
    }

    suspend fun attach(threadId: UUID, runtime: BackendConversationRuntime) {
        mutex.withLock {
            val state = states[threadId] ?: return@withLock
            if (state.terminal) return@withLock
            state.runtime = runtime
            state.runtimeReady.complete(Unit)
        }
    }

    suspend fun detach(threadId: UUID, runtime: BackendConversationRuntime) {
        mutex.withLock {
            val state = states[threadId] ?: return@withLock
            if (state.runtime === runtime) state.runtime = null
            removeIfTerminalAndIdle(threadId, state)
        }
    }

    suspend fun <T> acceptInput(
        threadId: UUID,
        requestId: String,
        device: ClientDevice,
        input: String,
        canAccept: suspend () -> Boolean,
        commit: suspend () -> T,
    ): T? {
        val runtimeReady = mutex.withLock { states[threadId]?.runtimeReady } ?: return null
        runtimeReady.await()
        return mutex.withLock {
            val state = states[threadId]?.takeUnless { it.terminal } ?: return@withLock null
            val runtime = state.runtime ?: return@withLock null
            if (!canAccept() || !runtime.submitToActiveRun(input)) return@withLock null
            val result = commit()
            state.latestDevice = device
            state.pendingAcks.putIfAbsent(requestId, CompletableDeferred())
            result
        }
    }

    suspend fun <T> withTerminalTransition(threadId: UUID, block: suspend () -> T): T =
        mutex.withLock {
            val state = states[threadId]
            val result = block()
            if (state != null) {
                state.terminal = true
                state.runtimeReady.complete(Unit)
                removeIfTerminalAndIdle(threadId, state)
            }
            result
        }

    suspend fun <T> acceptCancellation(
        threadId: UUID,
        requestId: String,
        canAccept: suspend () -> Boolean,
        commit: suspend () -> T,
    ): T? {
        val accepted = mutex.withLock {
            val state = states[threadId]?.takeUnless { it.terminal } ?: return@withLock false
            if (!canAccept()) return@withLock false
            state.pendingAcks.putIfAbsent(requestId, CompletableDeferred())
            true
        }
        if (!accepted) return null
        return try {
            commit()
        } catch (error: Exception) {
            ackSent(threadId, requestId)
            throw error
        }
    }

    suspend fun registerAck(threadId: UUID, requestId: String): Boolean = mutex.withLock {
        val state = states[threadId]?.takeUnless { it.terminal } ?: return@withLock false
        state.pendingAcks.putIfAbsent(requestId, CompletableDeferred())
        true
    }

    suspend fun ackSent(threadId: UUID, requestId: String) {
        val pending = mutex.withLock {
            val state = states[threadId] ?: return@withLock null
            state.pendingAcks.remove(requestId).also { removeIfTerminalAndIdle(threadId, state) }
        }
        pending?.complete(Unit)
    }

    suspend fun awaitAcceptedInputAcks(threadId: UUID) {
        while (true) {
            val pending = mutex.withLock { states[threadId]?.pendingAcks?.values?.toList() } ?: return
            if (pending.isEmpty()) return
            pending.forEach { it.await() }
        }
    }

    suspend fun beginTool(threadId: UUID, pending: PendingClientTool): Boolean = mutex.withLock {
        val state = states[threadId]?.takeUnless { it.terminal } ?: return@withLock false
        if (state.pendingTool != null) return@withLock false
        state.pendingTool = pending
        true
    }

    suspend fun finishTool(threadId: UUID, toolCallId: String, outcome: ClientToolOutcome): Boolean {
        val pending = mutex.withLock {
            val state = states[threadId] ?: return@withLock null
            state.pendingTool
                ?.takeIf { it.toolCallId == toolCallId }
                ?.also {
                    state.pendingTool = null
                    removeIfTerminalAndIdle(threadId, state)
                }
        } ?: return false
        return pending.result.complete(outcome)
    }

    suspend fun clearTool(threadId: UUID, toolCallId: String) {
        mutex.withLock {
            val state = states[threadId] ?: return@withLock
            if (state.pendingTool?.toolCallId == toolCallId) state.pendingTool = null
            removeIfTerminalAndIdle(threadId, state)
        }
    }

    private fun removeIfTerminalAndIdle(threadId: UUID, state: State) {
        if (state.terminal && state.runtime == null && state.pendingTool == null && state.pendingAcks.isEmpty()) {
            states.remove(threadId)
        }
    }
}
