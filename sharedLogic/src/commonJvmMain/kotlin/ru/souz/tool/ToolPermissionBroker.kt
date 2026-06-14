package ru.souz.tool

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.db.SettingsProvider

sealed interface ToolPermissionResult {
    object Ok : ToolPermissionResult
    class No(val msg: String) : ToolPermissionResult
}

data class ToolPermissionRequest(
    val id: Long,
    val description: String,
    val params: Map<String, String>,
)

interface ToolPermissionBroker {
    val requests: Flow<ToolPermissionRequest>

    suspend fun requestPermission(description: String, params: Map<String, String>): ToolPermissionResult

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
        params: Map<String, String>,
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
                    params = params,
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
