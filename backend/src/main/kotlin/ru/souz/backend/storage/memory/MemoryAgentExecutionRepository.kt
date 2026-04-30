package ru.souz.backend.storage.memory

import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.repository.AgentExecutionRepository

class MemoryAgentExecutionRepository : AgentExecutionRepository {
    private val mutex = Mutex()
    private val executions = LinkedHashMap<ExecutionKey, AgentExecution>()

    override suspend fun save(execution: AgentExecution): AgentExecution = mutex.withLock {
        executions[ExecutionKey(execution.userId, execution.id)] = execution
        execution
    }

    override suspend fun get(userId: String, executionId: UUID): AgentExecution? = mutex.withLock {
        executions[ExecutionKey(userId, executionId)]
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
}

private data class ExecutionKey(
    val userId: String,
    val executionId: UUID,
)
