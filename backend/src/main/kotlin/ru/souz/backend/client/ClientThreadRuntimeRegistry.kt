package ru.souz.backend.client

import com.fasterxml.jackson.databind.JsonNode
import java.net.InetAddress
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.souz.backend.agent.runtime.conversation.BackendConversationRuntime
import ru.souz.backend.client.repository.ClientRequestResult

internal data class ClientToolOutcome(
    val status: String,
    val result: JsonNode?,
    val error: ClientError?,
)

internal data class PendingClientTool(
    val toolCallId: String,
    val result: CompletableDeferred<ClientToolOutcome> = CompletableDeferred(),
)

internal sealed interface BeginClientToolResult {
    data class Started(val device: ClientDevice) : BeginClientToolResult
    data object Busy : BeginClientToolResult
    data object Missing : BeginClientToolResult
}

internal class ClientThreadRuntimeRegistry(
    val runtimeOwner: String = defaultRuntimeOwner(),
) {
    private data class State(
        val runtimeReady: CompletableDeferred<Unit> = CompletableDeferred(),
        var runtime: BackendConversationRuntime? = null,
        var latestDevice: ClientDevice,
        var pendingTool: PendingClientTool? = null,
        val pendingAcks: MutableMap<String, CompletableDeferred<Unit>> = linkedMapOf(),
        var terminal: Boolean = false,
        val removed: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private val mutex = Mutex()
    private val states = linkedMapOf<UUID, State>()

    suspend fun contains(threadId: UUID): Boolean = mutex.withLock {
        states.containsKey(threadId)
    }

    suspend fun isEmpty(): Boolean = mutex.withLock {
        states.isEmpty()
    }

    /** Suspends until [threadId] is no longer tracked, or returns immediately if it already isn't. */
    suspend fun awaitRemoved(threadId: UUID) {
        val removed = mutex.withLock { states[threadId]?.removed } ?: return
        removed.await()
    }

    suspend fun register(threadId: UUID, device: ClientDevice, requestId: String? = null) {
        mutex.withLock {
            val state = states.getOrPut(threadId) { State(latestDevice = device) }
            requestId?.let { state.pendingAcks.putIfAbsent(it, CompletableDeferred()) }
        }
    }

    suspend fun discard(threadId: UUID) {
        val discarded = mutex.withLock { states.remove(threadId) } ?: return
        discarded.runtimeReady.complete(Unit)
        discarded.pendingAcks.values.forEach { it.complete(Unit) }
        discarded.pendingTool?.result?.cancel()
        discarded.removed.complete(Unit)
    }

    suspend fun attach(threadId: UUID, runtime: BackendConversationRuntime) {
        mutex.withLock {
            val state = states[threadId] ?: return@withLock
            if (state.terminal) return@withLock
            state.runtime = runtime
        }
    }

    suspend fun markRuntimeReady(threadId: UUID, runtime: BackendConversationRuntime) {
        mutex.withLock {
            val state = states[threadId] ?: return@withLock
            if (state.terminal || state.runtime !== runtime) return@withLock
            state.runtimeReady.complete(Unit)
        }
    }

    suspend fun detach(threadId: UUID, runtime: BackendConversationRuntime) {
        mutex.withLock {
            val state = states[threadId] ?: return@withLock
            if (state.runtime === runtime) {
                state.runtime = null
                state.runtimeReady.complete(Unit)
            }
            removeIfTerminalAndIdle(threadId, state)
        }
    }

    suspend fun awaitRuntimeAvailable(threadId: UUID): Boolean {
        val runtimeReady = mutex.withLock { states[threadId]?.runtimeReady } ?: return false
        runtimeReady.await()
        return mutex.withLock {
            states[threadId]?.let { state -> !state.terminal && state.runtime != null } == true
        }
    }

    suspend fun acceptInput(
        threadId: UUID,
        requestId: String,
        device: ClientDevice,
        commit: suspend (afterSeq: Long) -> ClientRequestResult,
    ): ClientRequestResult? = mutex.withLock {
        val state = states[threadId]?.takeUnless { it.terminal } ?: return@withLock null
        val runtime = state.runtime ?: return@withLock null
        val committed = runtime.commitActiveRunInput { afterSeq ->
            withContext(NonCancellable) { commit(afterSeq) }
        }
        if (committed is ClientRequestResult.Accepted) {
            state.pendingAcks[requestId] = CompletableDeferred()
            state.latestDevice = device
        }
        committed
    }

    suspend fun commitCancellation(
        threadId: UUID,
        requestId: String,
        commit: suspend (runtimeAvailable: Boolean) -> ClientRequestResult,
        afterAccepted: suspend (ClientRequestResult.Accepted) -> Unit,
    ): ClientRequestResult = mutex.withLock {
        val state = states[threadId]?.takeUnless { it.terminal || it.runtime == null }
        withContext(NonCancellable) { commit(state != null) }.also { result ->
            if (state != null && result is ClientRequestResult.Accepted) {
                state.pendingAcks[requestId] = CompletableDeferred()
                state.terminal = true
                state.runtimeReady.complete(Unit)
                withContext(NonCancellable) { afterAccepted(result) }
                removeIfTerminalAndIdle(threadId, state)
            }
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

    suspend fun beginTool(threadId: UUID, pending: PendingClientTool): BeginClientToolResult = mutex.withLock {
        val state = states[threadId]?.takeUnless { it.terminal }
            ?: return@withLock BeginClientToolResult.Missing
        if (state.pendingTool != null) return@withLock BeginClientToolResult.Busy
        state.pendingTool = pending
        BeginClientToolResult.Started(state.latestDevice)
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
            state.removed.complete(Unit)
        }
    }

    companion object {
        val LEASE_DURATION: Duration = Duration.ofMinutes(2)
        val LEASE_REFRESH_INTERVAL: Duration = Duration.ofSeconds(30)

        fun leaseUntil(now: Instant = Instant.now()): Instant = now.plus(LEASE_DURATION)

        private fun defaultRuntimeOwner(): String =
            System.getProperty("souz.backend.instanceId")
                ?: System.getenv("SOUZ_BACKEND_INSTANCE_ID")
                ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull()
                ?: UUID.randomUUID().toString()
    }
}
