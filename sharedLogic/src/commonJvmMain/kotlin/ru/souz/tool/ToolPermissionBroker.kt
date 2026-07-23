package ru.souz.tool

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.db.SettingsProvider
import ru.souz.llms.ToolInvocationMeta

sealed interface ToolPermissionResult {
    object Ok : ToolPermissionResult
    class No(val msg: String) : ToolPermissionResult
}

data class ToolPermissionRequest(
    val id: Long,
    val description: String,
    val params: Map<String, String>,
)

/**
 * Minimal permission dependency consumed by tools that can run in a durable backend execution.
 *
 * Requesters may either return a decision immediately, suspend in-process, or throw an agent
 * execution-pause signal after durably parking the invocation. Invocation metadata is deliberately
 * part of this small contract so a backend requester can correlate the semantic tool invocation;
 * local requesters are free to ignore it.
 */
fun interface ToolPermissionRequester {
    suspend fun requestPermission(
        description: String,
        displayParams: Map<String, String>,
        meta: ToolInvocationMeta,
    ): ToolPermissionResult

    suspend fun requestPermission(
        description: String,
        displayParams: Map<String, String>,
    ): ToolPermissionResult = requestPermission(
        description = description,
        displayParams = displayParams,
        meta = ToolInvocationMeta.localDefault(),
    )
}

/** Local interactive permission flow used by the desktop and Android hosts. */
interface ToolPermissionBroker : ToolPermissionRequester {
    val requests: Flow<ToolPermissionRequest>

    suspend fun resolve(requestId: Long, approved: Boolean)
}

class ImmediateToolPermissionBroker(
    private val settingsProvider: SettingsProvider,
) : ToolPermissionBroker {
    private val requestMutex = Mutex()
    private val stateMutex = Mutex()
    private val requestsChannel = Channel<ToolPermissionRequest>(capacity = Channel.BUFFERED)
    private val pendingDecisions = LinkedHashMap<Long, CompletableDeferred<Boolean>>()
    private var nextRequestId = 0L

    override val requests: Flow<ToolPermissionRequest> = requestsChannel.receiveAsFlow()

    override suspend fun requestPermission(
        description: String,
        displayParams: Map<String, String>,
        meta: ToolInvocationMeta,
    ): ToolPermissionResult {
        if (!settingsProvider.safeModeEnabled) return ToolPermissionResult.Ok
        return requestMutex.withLock {
            val (id, deferred) = stateMutex.withLock {
                nextRequestId += 1
                val id = nextRequestId
                val deferred = CompletableDeferred<Boolean>()
                pendingDecisions[id] = deferred
                id to deferred
            }
            requestsChannel.send(
                ToolPermissionRequest(
                    id = id,
                    description = description,
                    params = displayParams,
                )
            )
            val approved = try {
                deferred.await()
            } finally {
                stateMutex.withLock {
                    pendingDecisions.remove(id)
                }
            }
            if (approved) ToolPermissionResult.Ok else toolPermissionForbid
        }
    }

    override suspend fun resolve(requestId: Long, approved: Boolean) {
        val deferred = stateMutex.withLock {
            pendingDecisions.remove(requestId)
        }
        deferred?.complete(approved)
    }

    private companion object {
        const val USER_DISAPPROVED_MESSAGE = "User disapproved"
        val toolPermissionForbid = ToolPermissionResult.No(USER_DISAPPROVED_MESSAGE)
    }
}
