package ru.gigadesk.tool

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.gigadesk.db.SettingsProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

sealed interface ToolPermissionResult {
    object Ok : ToolPermissionResult
    class No(val msg: String) : ToolPermissionResult
}

data class ToolPermissionRequest(
    val id: Long,
    val description: String,
    val params: Map<String, String>,
)

class ToolPermissionBroker(
    private val settingsProvider: SettingsProvider,
) {
    private val requestId = AtomicLong(0L)
    private val requestMutex = Mutex()
    private val requestsChannel = Channel<ToolPermissionRequest>(capacity = Channel.BUFFERED)
    private val pendingDecisions = ConcurrentHashMap<Long, CompletableDeferred<Boolean>>()

    val requests: Flow<ToolPermissionRequest> = requestsChannel.receiveAsFlow()

    suspend fun requestPermission(description: String, params: Map<String, String>): ToolPermissionResult {
        if (!settingsProvider.safeModeEnabled) return ToolPermissionResult.Ok
        return requestMutex.withLock {
            val id = requestId.incrementAndGet()
            val deferred = CompletableDeferred<Boolean>()
            pendingDecisions[id] = deferred
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
                pendingDecisions.remove(id)
            }
            if (approved) ToolPermissionResult.Ok else toolPermissionForbid
        }
    }

    fun resolve(requestId: Long, approved: Boolean) {
        pendingDecisions.remove(requestId)?.complete(approved)
    }

    private companion object {
        const val USER_DISAPPROVED_MESSAGE = "User disapproved"
        val toolPermissionForbid = ToolPermissionResult.No(USER_DISAPPROVED_MESSAGE)
    }
}
