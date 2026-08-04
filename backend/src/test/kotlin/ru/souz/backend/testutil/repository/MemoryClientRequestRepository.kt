package ru.souz.backend.testutil.repository

import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.backend.client.model.ClientRequest
import ru.souz.backend.client.repository.ClientRequestRepository
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.repository.AgentExecutionRepository

class MemoryClientRequestRepository(
    private val executionRepository: AgentExecutionRepository,
) : ClientRequestRepository {
    private val mutex = Mutex()
    private val requests = linkedMapOf<Pair<UUID, String>, ClientRequest>()

    override suspend fun create(request: ClientRequest): ClientRequest = mutex.withLock {
        val key = request.chatId to request.requestId
        check(key !in requests) { "Client request already exists: ${request.chatId}/${request.requestId}" }
        requests[key] = request
        request
    }

    override suspend fun createWithExecution(
        execution: AgentExecution,
        request: ClientRequest,
    ): AgentExecution = mutex.withLock {
        require(request.chatId == execution.chatId) { "Client request and execution must belong to the same chat." }
        require(request.threadId == execution.id) { "Client request must reference the created execution." }
        val key = request.chatId to request.requestId
        check(key !in requests) { "Client request already exists: ${request.chatId}/${request.requestId}" }
        executionRepository.create(execution).also { requests[key] = request }
    }

    override suspend fun get(chatId: UUID, requestId: String): ClientRequest? = mutex.withLock {
        requests[chatId to requestId]
    }
}
