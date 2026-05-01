package ru.souz.backend.storage.filesystem

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.isActive
import ru.souz.backend.execution.repository.ActiveAgentExecutionConflictException
import ru.souz.backend.execution.repository.AgentExecutionRepository

class FilesystemAgentExecutionRepository(
    dataDir: java.nio.file.Path,
    private val mapper: ObjectMapper = filesystemStorageObjectMapper(),
) : AgentExecutionRepository {
    private val mutex = Mutex()
    private val layout = FilesystemStorageLayout(dataDir)

    override suspend fun create(execution: AgentExecution): AgentExecution = mutex.withLock {
        filesystemIo {
            val executions = loadExecutions(execution.userId, execution.chatId)
            registerActiveExecution(execution = execution, currentExecutions = executions)
            appendExecution(execution)
        }
    }

    override suspend fun update(execution: AgentExecution): AgentExecution = mutex.withLock {
        filesystemIo {
            val executions = loadExecutions(execution.userId, execution.chatId)
            registerActiveExecution(execution = execution, currentExecutions = executions)
            appendExecution(execution)
        }
    }

    override suspend fun get(userId: String, executionId: UUID): AgentExecution? = mutex.withLock {
        filesystemIo {
            loadAllExecutions(userId).firstOrNull { it.id == executionId }
        }
    }

    override suspend fun getByChat(
        userId: String,
        chatId: UUID,
        executionId: UUID,
    ): AgentExecution? = mutex.withLock {
        filesystemIo {
            loadExecutions(userId, chatId).firstOrNull { it.id == executionId }
        }
    }

    override suspend fun findActive(userId: String, chatId: UUID): AgentExecution? = mutex.withLock {
        filesystemIo {
            loadExecutions(userId, chatId)
                .firstOrNull { it.status.isActive() }
        }
    }

    override suspend fun listByChat(
        userId: String,
        chatId: UUID,
        limit: Int,
    ): List<AgentExecution> = mutex.withLock {
        filesystemIo {
            loadExecutions(userId, chatId)
                .sortedByDescending { it.startedAt }
                .take(limit)
        }
    }

    private fun appendExecution(execution: AgentExecution): AgentExecution {
        appendJsonLine(
            target = layout.executionsFile(execution.userId, execution.chatId),
            line = mapper.writeValueAsString(execution.toStored()),
        )
        return execution
    }

    private fun loadExecutions(userId: String, chatId: UUID): List<AgentExecution> =
        readLinesIfExists(layout.executionsFile(userId, chatId))
            .map { mapper.readValue<StoredAgentExecution>(it).toDomain() }
            .associateBy { it.id }
            .values
            .sortedByDescending { it.startedAt }

    private fun loadAllExecutions(userId: String): List<AgentExecution> =
        layout.chatDirectories(userId)
            .flatMap { chatDirectory ->
                readLinesIfExists(chatDirectory.resolve("executions.jsonl"))
                    .map { mapper.readValue<StoredAgentExecution>(it).toDomain() }
            }
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
