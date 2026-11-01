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
import kotlinx.coroutines.withTimeoutOrNull
import ru.souz.backend.agent.runtime.conversation.BackendConversationRuntime
import ru.souz.backend.client.repository.ClientRequestResult
import ru.souz.backend.toolcall.repository.ToolCallContext

internal data class ClientToolOutcome(
    val status: String,
    val result: JsonNode?,
    val error: ClientError?,
)

internal class PendingChannelTool(private val deadlineAt: Instant) {
    private val received = CompletableDeferred<Boolean>()
    val result = CompletableDeferred<ClientToolOutcome>()

    fun accept(now: Instant): Boolean = now.isBefore(deadlineAt) && received.complete(true)

    suspend fun awaitResult(now: Instant): ClientToolOutcome {
        if (withTimeoutOrNull(Duration.between(now, deadlineAt).toMillis()) { received.await() } == null) {
            received.complete(false)
        }
        // Receipt and timeout compete once; a received result then waits for its ACK, without the deadline.
        return if (received.await()) result.await() else ClientToolOutcome(
            "timed_out", null, ClientError("client_tool_timed_out", "Client tool result deadline expired."),
        )
    }
}

/**
 * Tracks process-local thread runtimes and tool waiters.
 * [runtimeOwner] identifies this backend instance in PostgreSQL so lease renewals and turn commits
 * can verify ownership. Live thread commands require sticky routing to the instance holding the runtime.
 */
internal class ClientThreadRuntimeRegistry(
    val runtimeOwner: String = defaultRuntimeOwner(),
) {
    private data class State(
        val runtimeReady: CompletableDeferred<Unit> = CompletableDeferred(),
        val runtime: BackendConversationRuntime? = null,
        val latestDevice: ClientDevice,
        val terminal: Boolean = false,
        val removed: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private val mutex = Mutex()
    private val states = linkedMapOf<UUID, State>()
    private val channelTools = mutableMapOf<ToolCallContext, PendingChannelTool>()

    // Local calls keep their source thread ID; remote calls use a technical correlation ID.
    suspend fun <T> withChannelTool(
        context: ToolCallContext,
        deadlineAt: Instant,
        block: suspend (PendingChannelTool) -> T,
    ): T {
        val pending = PendingChannelTool(deadlineAt)
        try {
            mutex.withLock { channelTools[context] = pending }
            return block(pending)
        } finally {
            withContext(NonCancellable) { mutex.withLock { channelTools.remove(context) } }
            pending.result.cancel()
        }
    }

    suspend fun acceptChannelTool(context: ToolCallContext, now: Instant): PendingChannelTool? =
        mutex.withLock { channelTools[context]?.takeIf { it.accept(now) } }

    suspend fun contains(threadId: UUID): Boolean = mutex.withLock {
        states.containsKey(threadId)
    }

    suspend fun isEmpty(): Boolean = mutex.withLock {
        states.isEmpty() && channelTools.isEmpty()
    }

    /** Suspends until [threadId] is no longer tracked, or returns immediately if it already isn't. */
    suspend fun awaitRemoved(threadId: UUID) {
        val removed = mutex.withLock { states[threadId]?.removed } ?: return
        removed.await()
    }

    suspend fun register(threadId: UUID, device: ClientDevice) {
        mutex.withLock {
            states.getOrPut(threadId) { State(latestDevice = device) }
        }
    }

    suspend fun discard(threadId: UUID) {
        val discarded = mutex.withLock { states.remove(threadId) } ?: return
        discarded.runtimeReady.complete(Unit)
        discarded.removed.complete(Unit)
    }

    suspend fun attach(threadId: UUID, runtime: BackendConversationRuntime) {
        mutex.withLock {
            val state = states[threadId] ?: return@withLock
            if (state.terminal) return@withLock
            states[threadId] = state.copy(runtime = runtime)
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
            val detached = if (state.runtime === runtime) {
                state.runtimeReady.complete(Unit)
                state.copy(runtime = null)
            } else state
            storeOrRemove(threadId, detached)
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
        device: ClientDevice,
        commit: suspend (afterSeq: Long) -> ClientRequestResult,
    ): ClientRequestResult? = mutex.withLock {
        val state = states[threadId]?.takeUnless { it.terminal } ?: return@withLock null
        val runtime = state.runtime ?: return@withLock null
        runtime.commitActiveRunInput { afterSeq ->
            withContext(NonCancellable) {
                commit(afterSeq).also { result ->
                    if (result is ClientRequestResult.Accepted) {
                        states[threadId] = state.copy(latestDevice = device)
                    }
                }
            }
        }
    }

    suspend fun commitCancellation(
        threadId: UUID,
        commit: suspend (runtimeAvailable: Boolean) -> ClientRequestResult,
    ): ClientRequestResult = mutex.withLock {
        val state = states[threadId]?.takeUnless { it.terminal || it.runtime == null }
        withContext(NonCancellable) { commit(state != null) }.also { result ->
            if (state != null && result is ClientRequestResult.Accepted) {
                state.runtimeReady.complete(Unit)
                storeOrRemove(threadId, state.copy(terminal = true))
            }
        }
    }

    suspend fun <T> withTerminalTransition(threadId: UUID, block: suspend () -> T): T =
        mutex.withLock {
            val state = states[threadId]
            val result = block()
            if (state != null) {
                state.runtimeReady.complete(Unit)
                storeOrRemove(threadId, state.copy(terminal = true))
            }
            result
        }

    suspend fun currentDevice(threadId: UUID): ClientDevice? = mutex.withLock {
        states[threadId]?.takeUnless { it.terminal }?.latestDevice
    }

    private fun storeOrRemove(threadId: UUID, state: State) {
        if (state.terminal && state.runtime == null) {
            states.remove(threadId)
            state.removed.complete(Unit)
        } else {
            states[threadId] = state
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
