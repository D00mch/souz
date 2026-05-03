package ru.souz.backend.execution.repository

import java.util.UUID
import ru.souz.backend.execution.model.AgentExecution

class ActiveAgentExecutionConflictException(
    val userId: String,
    val chatId: UUID,
) : RuntimeException("Chat $chatId for user $userId already has an active execution.")

interface AgentExecutionRepository {
    suspend fun create(execution: AgentExecution): AgentExecution
    suspend fun update(execution: AgentExecution): AgentExecution
    suspend fun get(userId: String, executionId: UUID): AgentExecution?
    suspend fun getByChat(userId: String, chatId: UUID, executionId: UUID): AgentExecution?
    suspend fun findActive(userId: String, chatId: UUID): AgentExecution?
    suspend fun listByChat(
        userId: String,
        chatId: UUID,
        limit: Int = DEFAULT_LIMIT,
    ): List<AgentExecution>

    companion object {
        const val DEFAULT_LIMIT: Int = 50
    }
}
