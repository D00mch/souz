package ru.souz.backend.testutil.repository

import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.backend.client.model.ClientRequest
import ru.souz.backend.client.repository.ClientRequestRepository

class MemoryClientRequestRepository : ClientRequestRepository {
    private val mutex = Mutex()
    private val requests = linkedMapOf<Pair<UUID, String>, ClientRequest>()

    override suspend fun create(request: ClientRequest): ClientRequest = mutex.withLock {
        requests[request.chatId to request.requestId] = request
        request
    }

    override suspend fun get(chatId: UUID, requestId: String): ClientRequest? = mutex.withLock {
        requests[chatId to requestId]
    }
}
