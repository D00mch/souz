package ru.souz.tool.telegram

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class SelectionRequest<TId, TCandidate>(
    val id: Long,
    val query: String,
    val candidates: List<TCandidate>,
)

open class SelectionBroker<TId, TCandidate> {
    private val requestId = AtomicLong(0L)
    private val requestMutex = Mutex()
    private val requestsChannel = Channel<SelectionRequest<TId, TCandidate>>(capacity = Channel.BUFFERED)
    private val pendingSelections = ConcurrentHashMap<Long, CompletableDeferred<TId?>>()

    val requests: Flow<SelectionRequest<TId, TCandidate>> = requestsChannel.receiveAsFlow()

    suspend fun requestSelection(query: String, candidates: List<TCandidate>): TId? {
        require(candidates.isNotEmpty()) { "candidates must not be empty" }

        return requestMutex.withLock {
            val id = requestId.incrementAndGet()
            val deferred = CompletableDeferred<TId?>()
            pendingSelections[id] = deferred
            requestsChannel.send(
                SelectionRequest(
                    id = id,
                    query = query,
                    candidates = candidates,
                )
            )
            try {
                deferred.await()
            } finally {
                pendingSelections.remove(id)
            }
        }
    }

    fun resolve(requestId: Long, selectedId: TId?) {
        pendingSelections.remove(requestId)?.complete(selectedId)
    }
}
