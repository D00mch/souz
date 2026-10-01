package ru.souz.backend.execution.repository

import java.time.Instant
import java.util.UUID
import ru.souz.backend.agent.session.AgentConversationState
import ru.souz.backend.chat.model.ChatMessage
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.execution.model.AgentExecutionUsage

class ActiveAgentExecutionConflictException(
    val userId: String,
    val chatId: UUID,
) : RuntimeException("Chat $chatId for user $userId already has an active execution.")

interface AgentExecutionRepository {
    suspend fun create(execution: AgentExecution): AgentExecution
    /** Changes lifecycle fields only, guarded by status, cancellation intent and runtime owner. */
    suspend fun transitionIfCurrent(
        expected: AgentExecution,
        status: AgentExecutionStatus,
        errorCode: String? = null,
        errorMessage: String? = null,
        usage: AgentExecutionUsage? = null,
    ): AgentExecution?
    /** Saves a running turn and its transition in one transaction, or returns null if ownership was lost. */
    suspend fun commitTurn(
        expected: AgentExecution,
        state: AgentConversationState,
        usage: AgentExecutionUsage,
        output: String? = null,
        assistantMessageId: UUID? = null,
    ): CommittedAgentTurn?
    suspend fun start(execution: AgentExecution, userMessageId: UUID): AgentExecution?
    suspend fun get(userId: String, executionId: UUID): AgentExecution?
    suspend fun getByChat(userId: String, chatId: UUID, executionId: UUID): AgentExecution?
    suspend fun findByClientMessageId(userId: String, chatId: UUID, clientMessageId: String): AgentExecution?
    suspend fun findActive(userId: String, chatId: UUID): AgentExecution?
    suspend fun refreshClientThreadLease(
        userId: String,
        chatId: UUID,
        executionId: UUID,
        runtimeOwner: String,
        leaseUntil: Instant,
    ): AgentExecution? = null
    suspend fun failInterruptedClientThreads(now: Instant): List<AgentExecution> = emptyList()
    suspend fun findRecoveredClientThreadsMissingTerminalEvents(): List<AgentExecution> = emptyList()
    suspend fun listByChat(
        userId: String,
        chatId: UUID,
        limit: Int = DEFAULT_LIMIT,
    ): List<AgentExecution>

    companion object {
        const val DEFAULT_LIMIT: Int = 50
    }
}

data class CommittedAgentTurn(
    val execution: AgentExecution,
    val assistantMessage: ChatMessage?,
    val assistantMessageCreated: Boolean,
)
