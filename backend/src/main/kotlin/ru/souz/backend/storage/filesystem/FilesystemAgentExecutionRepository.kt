package ru.souz.backend.storage.filesystem

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.isActive
import ru.souz.backend.execution.repository.ActiveAgentExecutionConflictException
import ru.souz.backend.execution.repository.AgentExecutionRepository

class FilesystemAgentExecutionRepository(
    dataDir: java.nio.file.Path,
    mapper: ObjectMapper = filesystemStorageObjectMapper(),
) : BaseFilesystemRepository(dataDir, mapper), AgentExecutionRepository {

    override suspend fun create(execution: AgentExecution): AgentExecution =
        withFileLock {
            val executions = loadExecutions(execution.userId, execution.chatId)
            registerActiveExecution(execution = execution, currentExecutions = executions)
            appendExecution(execution)
        }

    override suspend fun update(execution: AgentExecution): AgentExecution =
        withFileLock {
            val executions = loadExecutions(execution.userId, execution.chatId)
            registerActiveExecution(execution = execution, currentExecutions = executions)
            appendExecution(execution)
        }

    override suspend fun get(userId: String, executionId: UUID): AgentExecution? =
        withFileLock { loadAllExecutions(userId).firstOrNull { it.id == executionId } }

    override suspend fun getByChat(
        userId: String,
        chatId: UUID,
        executionId: UUID,
    ): AgentExecution? =
        withFileLock {
            loadExecutions(userId, chatId).firstOrNull { it.id == executionId }
        }

    override suspend fun findByClientMessageId(
        userId: String,
        chatId: UUID,
        clientMessageId: String,
    ): AgentExecution? =
        withFileLock {
            loadExecutions(userId, chatId).firstOrNull { it.clientMessageId == clientMessageId }
        }

    override suspend fun findActive(userId: String, chatId: UUID): AgentExecution? =
        withFileLock {
            loadExecutions(userId, chatId)
                .firstOrNull { it.status.isActive() }
        }

    override suspend fun listByChat(
        userId: String,
        chatId: UUID,
        limit: Int,
    ): List<AgentExecution> =
        withFileLock {
            loadExecutions(userId, chatId)
                .sortedByDescending { it.startedAt }
                .take(limit)
        }

    private fun appendExecution(execution: AgentExecution): AgentExecution {
        mapper.appendJsonValue(
            target = layout.executionsFile(execution.userId, execution.chatId),
            value = execution.toStored(),
        )
        return execution
    }

    private fun loadExecutions(userId: String, chatId: UUID): List<AgentExecution> =
        mapper.readJsonLines<StoredAgentExecution>(layout.executionsFile(userId, chatId))
            .map(StoredAgentExecution::toDomain)
            .associateBy { it.id }
            .values
            .sortedByDescending { it.startedAt }

    private fun loadAllExecutions(userId: String): List<AgentExecution> =
        mapper.readJsonLinesFromChatDirectories<StoredAgentExecution>(layout, userId, "executions.jsonl")
            .map(StoredAgentExecution::toDomain)
            .associateBy { it.id }
            .values
            .sortedByDescending { it.startedAt }

    private fun registerActiveExecution(
        execution: AgentExecution,
        currentExecutions: List<AgentExecution>,
    ) {
        if (!execution.status.isActive()) {
            return
        }
        val active = currentExecutions.firstOrNull { it.status.isActive() && it.id != execution.id }
        if (active != null) {
            throw ActiveAgentExecutionConflictException(
                userId = execution.userId,
                chatId = execution.chatId,
            )
        }
    }
}
