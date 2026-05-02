package ru.souz.backend.storage.memory

import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.isActive
import ru.souz.backend.execution.repository.ActiveAgentExecutionConflictException
import ru.souz.backend.execution.repository.AgentExecutionRepository

class MemoryAgentExecutionRepository(
    maxEntries: Int,
) : AgentExecutionRepository {
    private val mutex = Mutex()
    private val executions = boundedLruMap<ExecutionKey, AgentExecution>(maxEntries)
    private val activeExecutionIds = HashMap<ActiveConversationKey, UUID>()
    private val activeExecutions = HashMap<ExecutionKey, AgentExecution>()

    constructor() : this(DEFAULT_MEMORY_REPOSITORY_MAX_ENTRIES)

    override suspend fun create(execution: AgentExecution): AgentExecution = mutex.withLock {
        ensureNoActiveExecutionConflict(execution)
        storeExecution(execution)
    }

    override suspend fun update(execution: AgentExecution): AgentExecution = mutex.withLock {
        ensureNoActiveExecutionConflict(execution)
        storeExecution(execution)
    }

    override suspend fun get(userId: String, executionId: UUID): AgentExecution? = mutex.withLock {
        executionFor(ExecutionKey(userId, executionId))
    }

    override suspend fun getByChat(
        userId: String,
        chatId: UUID,
        executionId: UUID,
    ): AgentExecution? = mutex.withLock {
        executionFor(ExecutionKey(userId, executionId))?.takeIf { it.chatId == chatId }
    }

    override suspend fun findActive(userId: String, chatId: UUID): AgentExecution? = mutex.withLock {
        activeExecutionIds[ActiveConversationKey(userId, chatId)]
            ?.let { executionId -> executionFor(ExecutionKey(userId, executionId)) }
    }

    override suspend fun listByChat(
        userId: String,
        chatId: UUID,
        limit: Int,
    ): List<AgentExecution> = mutex.withLock {
        (executions.values + activeExecutions.values)
            .distinctBy { it.id }
            .asSequence()
            .filter { it.userId == userId && it.chatId == chatId }
            .sortedByDescending { it.startedAt }
            .take(limit)
            .toList()
    }

    private fun ensureNoActiveExecutionConflict(execution: AgentExecution) {
        if (!execution.status.isActive()) {
            return
        }
        val conversationKey = ActiveConversationKey(execution.userId, execution.chatId)
        val activeExecutionId = activeExecutionIds[conversationKey]
        if (activeExecutionId != null && activeExecutionId != execution.id) {
            throw ActiveAgentExecutionConflictException(
                userId = execution.userId,
                chatId = execution.chatId,
            )
        }
    }

    private fun storeExecution(execution: AgentExecution): AgentExecution {
        val key = ExecutionKey(execution.userId, execution.id)
        executions[key] = execution
        syncActiveExecution(key, execution)
        return execution
    }

    private fun executionFor(key: ExecutionKey): AgentExecution? =
        executions[key] ?: activeExecutions[key]

    private fun syncActiveExecution(
        key: ExecutionKey,
        execution: AgentExecution,
    ) {
        val conversationKey = ActiveConversationKey(execution.userId, execution.chatId)
        if (execution.status.isActive()) {
            activeExecutionIds[conversationKey] = execution.id
            activeExecutions[key] = execution
        } else {
            if (activeExecutionIds[conversationKey] == execution.id) {
                activeExecutionIds.remove(conversationKey)
            }
            activeExecutions.remove(key)
        }
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
