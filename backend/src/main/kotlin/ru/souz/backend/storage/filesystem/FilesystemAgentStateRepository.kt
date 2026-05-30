package ru.souz.backend.storage.filesystem

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.util.UUID
import org.slf4j.LoggerFactory
import ru.souz.backend.agent.session.AgentConversationState
import ru.souz.backend.agent.session.AgentStateRepository

class FilesystemAgentStateRepository(
    dataDir: java.nio.file.Path,
    mapper: ObjectMapper = filesystemStorageObjectMapper(),
) : BaseFilesystemRepository(dataDir, mapper), AgentStateRepository {
    private val log = LoggerFactory.getLogger(FilesystemAgentStateRepository::class.java)

    override suspend fun get(userId: String, chatId: UUID): AgentConversationState? =
        withFileLock {
            val path = layout.agentStateFile(userId, chatId)
            val raw = readTextIfExists(path) ?: return@withFileLock null
            runCatching { mapper.readValue<StoredAgentConversationState>(raw).toDomain() }
                .onFailure { error ->
                    log.warn(
                        "Failed to read agent state from {}: {}. Returning null.",
                        path,
                        error.message,
                    )
                }
                .getOrNull()
        }

    override suspend fun save(state: AgentConversationState): AgentConversationState =
        withFileLock {
            mapper.writeJsonFile(
                target = layout.agentStateFile(state.userId, state.chatId),
                value = state.toStored(),
            )
            state
        }
}
