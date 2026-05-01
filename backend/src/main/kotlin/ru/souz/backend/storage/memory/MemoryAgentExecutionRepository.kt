package ru.souz.backend.storage.memory

import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.isActive
import ru.souz.backend.execution.repository.ActiveAgentExecutionConflictException
import ru.souz.backend.execution.repository.AgentExecutionRepository

class MemoryAgentExecutionRepository : AgentExecutionRepository {
    private val mutex = Mutex()
    private val executions = LinkedHashMap<ExecutionKey, AgentExecution>()
    private val activeExecutions = LinkedHashMap<ActiveConversationKey, UUID>()

    override suspend fun create(execution: AgentExecution): AgentExecution = mutex.withLock {
        registerActiveExecution(execution)
        executions[ExecutionKey(execution.userId, execution.id)] = execution
        execution
    }

    override suspend fun update(execution: AgentExecution): AgentExecution = mutex.withLock {
        registerActiveExecution(execution)
        executions[ExecutionKey(execution.userId, execution.id)]
            ?.takeIf { !execution.status.isActive() }
            ?.let { existing ->
                if (activeExecutions[ActiveConversationKey(existing.userId, existing.chatId)] == existing.id) {
                    activeExecutions.remove(ActiveConversationKey(existing.userId, existing.chatId))
                }
            }

        executions[ExecutionKey(execution.userId, execution.id)] = execution
        if (!execution.status.isActive()) {
            val conversationKey = ActiveConversationKey(execution.userId, execution.chatId)
            if (activeExecutions[conversationKey] == execution.id) {
                activeExecutions.remove(conversationKey)
            }
        }
        execution
    }

    override suspend fun get(userId: String, executionId: UUID): AgentExecution? = mutex.withLock {
        executions[ExecutionKey(userId, executionId)]
    }

    override suspend fun getByChat(
        userId: String,
        chatId: UUID,
        executionId: UUID,
    ): AgentExecution? = mutex.withLock {
        executions[ExecutionKey(userId, executionId)]?.takeIf { it.chatId == chatId }
    }

    override suspend fun findActive(userId: String, chatId: UUID): AgentExecution? = mutex.withLock {
        activeExecutions[ActiveConversationKey(userId, chatId)]
            ?.let { executionId -> executions[ExecutionKey(userId, executionId)] }
    }

    override suspend fun listByChat(
        userId: String,
        chatId: UUID,
        limit: Int,
    ): List<AgentExecution> = mutex.withLock {
        executions.values
            .asSequence()
            .filter { it.userId == userId && it.chatId == chatId }
            .sortedByDescending { it.startedAt }
            .take(limit)
            .toList()
    }

    private fun registerActiveExecution(execution: AgentExecution) {
        if (!execution.status.isActive()) {
            return
        }
        val conversationKey = ActiveConversationKey(execution.userId, execution.chatId)
        val activeExecutionId = activeExecutions[conversationKey]
        if (activeExecutionId != null && activeExecutionId != execution.id) {
            throw ActiveAgentExecutionConflictException(
                userId = execution.userId,
                chatId = execution.chatId,
            )
        }
        activeExecutions[conversationKey] = execution.id
    }
}

private data class ExecutionKey(
    val userId: String,
    val executionId: UUID,
)

private data class ActiveConversationKey(
    val userId: String,
    val chatId: UUID,
)
