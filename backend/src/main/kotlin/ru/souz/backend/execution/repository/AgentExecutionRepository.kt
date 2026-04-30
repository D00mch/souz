package ru.souz.backend.execution.repository

import java.util.UUID
import ru.souz.backend.execution.model.AgentExecution

interface AgentExecutionRepository {
    suspend fun save(execution: AgentExecution): AgentExecution
    suspend fun get(userId: String, executionId: UUID): AgentExecution?
    suspend fun listByChat(
        userId: String,
        chatId: UUID,
        limit: Int = DEFAULT_LIMIT,
    ): List<AgentExecution>

    companion object {
        const val DEFAULT_LIMIT: Int = 50
    }
}
