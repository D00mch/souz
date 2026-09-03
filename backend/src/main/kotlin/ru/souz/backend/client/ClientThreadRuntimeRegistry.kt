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
        val chatId: UUID,
        val runtimeReady: CompletableDeferred<Unit> = CompletableDeferred(),
        var runtime: BackendConversationRuntime? = null,
        var latestDevice: ClientDevice,
        var historyEligible: Boolean = true,
        var pendingHistoryThroughSeq: Long? = null,
        var pendingTool: PendingClientTool? = null,
        val pendingAcks: MutableMap<String, CompletableDeferred<Unit>> = linkedMapOf(),
        var terminal: Boolean = false,
        val removed: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private val mutex = Mutex()
    private val states = linkedMapOf<UUID, State>()
    private val threadByChat = linkedMapOf<UUID, UUID>()

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

    suspend fun register(chatId: UUID, threadId: UUID, device: ClientDevice, requestId: String? = null) {
        mutex.withLock {
            val state = states.getOrPut(threadId) { State(chatId = chatId, latestDevice = device) }
            check(state.chatId == chatId) { "Thread is already registered for another chat" }
            val indexedState = threadByChat[chatId]?.let(states::get)
            if (indexedState == null || indexedState.terminal) threadByChat[chatId] = threadId
            requestId?.let { state.pendingAcks.putIfAbsent(it, CompletableDeferred()) }
        }
    }

    suspend fun discard(threadId: UUID) {
        var historyTarget: HistoryTarget? = null
        val discarded = mutex.withLock {
            states.remove(threadId)?.also { discardedState ->
                if (threadByChat[discardedState.chatId] == threadId) {
                    val replacement = states.entries.firstOrNull { (_, state) ->
                        state.chatId == discardedState.chatId && !state.terminal
                    }
                    if (replacement == null) {
                        threadByChat.remove(discardedState.chatId)
                    } else {
                        threadByChat[discardedState.chatId] = replacement.key
                        discardedState.pendingHistoryThroughSeq?.let { throughSeq ->
                            replacement.value.pendingHistoryThroughSeq = maxOf(
                                replacement.value.pendingHistoryThroughSeq ?: 0L,
                                throughSeq,
                            )
                        }
                        historyTarget = replacement.value.historyTarget(replacement.key)
                    }
                }
            }
        } ?: return
        discarded.runtimeReady.complete(Unit)
        discarded.pendingAcks.values.forEach { it.complete(Unit) }
        discarded.pendingTool?.result?.cancel()
        discarded.removed.complete(Unit)
        historyTarget?.let { forwardPendingHistory(it.threadId, it.runtime, it.throughSeq) }
    }

    suspend fun attach(
        threadId: UUID,
        runtime: BackendConversationRuntime,
        historyEligible: Boolean,
    ) {
        val historyTarget = mutex.withLock {
            val state = states[threadId] ?: return@withLock null
            if (state.terminal) return@withLock null
            state.runtime = runtime
            state.historyEligible = historyEligible
            if (!historyEligible) state.pendingHistoryThroughSeq = null
            state.historyTarget(threadId)
        }
        historyTarget?.let { forwardPendingHistory(it.threadId, it.runtime, it.throughSeq) }
    }

    suspend fun markRuntimeReady(threadId: UUID, runtime: BackendConversationRuntime) {
        mutex.withLock {
            val state = states[threadId] ?: return@withLock
            if (state.terminal || state.runtime !== runtime) return@withLock
            state.runtimeReady.complete(Unit)
        }
    }

    /** Signals the locally tracked runtime for [chatId] without selecting or mutating a thread. */
    suspend fun notifyHistoryPending(chatId: UUID, throughSeq: Long): Boolean {
        val target = mutex.withLock {
            val threadId = threadByChat[chatId] ?: return@withLock null
            val state = states[threadId]?.takeUnless { it.terminal } ?: return@withLock null
            if (!state.historyEligible) return@withLock null
            state.pendingHistoryThroughSeq = maxOf(state.pendingHistoryThroughSeq ?: 0L, throughSeq)
            state.runtime
                ?.let { runtime -> HistoryTarget(threadId, runtime, state.pendingHistoryThroughSeq!!) }
        } ?: return false
        return forwardPendingHistory(target.threadId, target.runtime, target.throughSeq)
    }

    suspend fun detach(threadId: UUID, runtime: BackendConversationRuntime) {
        mutex.withLock {
            val state = states[threadId] ?: return@withLock
            if (state.runtime === runtime) {
                state.runtime = null
                state.historyEligible = false
                state.pendingHistoryThroughSeq = null
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
            state.historyEligible = true
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
            if (threadByChat[state.chatId] == threadId) threadByChat.remove(state.chatId)
            state.removed.complete(Unit)
        }
    }

    private suspend fun forwardPendingHistory(
        threadId: UUID,
        runtime: BackendConversationRuntime,
        throughSeq: Long,
    ): Boolean {
        val accepted = runtime.notifyHistoryPending(throughSeq)
        if (accepted) {
            mutex.withLock {
                val state = states[threadId]
                if (
                    state?.runtime === runtime &&
                    state.pendingHistoryThroughSeq?.let { it <= throughSeq } == true
                ) {
                    state.pendingHistoryThroughSeq = null
                }
            }
        }
        return accepted
    }

    private fun State.historyTarget(threadId: UUID): HistoryTarget? =
        pendingHistoryThroughSeq
            ?.takeIf { historyEligible }
            ?.let { throughSeq -> runtime?.let { HistoryTarget(threadId, it, throughSeq) } }

    private data class HistoryTarget(
        val threadId: UUID,
        val runtime: BackendConversationRuntime,
        val throughSeq: Long,
    )

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
