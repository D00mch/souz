package ru.souz.backend.client.repository

import java.util.UUID
import ru.souz.backend.client.model.ClientRequest
import ru.souz.backend.execution.model.AgentExecution

interface ClientRequestRepository {
    suspend fun create(request: ClientRequest): ClientRequest
    suspend fun createWithExecution(execution: AgentExecution, request: ClientRequest): AgentExecution
    suspend fun get(chatId: UUID, requestId: String): ClientRequest?
}
