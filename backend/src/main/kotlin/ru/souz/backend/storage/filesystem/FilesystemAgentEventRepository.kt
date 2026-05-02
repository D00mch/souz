package ru.souz.backend.storage.filesystem

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import ru.souz.backend.events.model.AgentEvent
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.events.repository.AgentEventRepository

class FilesystemAgentEventRepository(
    dataDir: java.nio.file.Path,
    mapper: ObjectMapper = filesystemStorageObjectMapper(),
) : BaseFilesystemRepository(dataDir, mapper), AgentEventRepository {

    override suspend fun append(
        userId: String,
        chatId: UUID,
        executionId: UUID?,
        type: AgentEventType,
        payload: Map<String, String>,
        id: UUID,
        createdAt: Instant,
    ): AgentEvent =
        withFileLock {
            val currentEvents = loadEvents(userId, chatId)
            val nextSeq = currentEvents.maxOfOrNull { it.seq }?.plus(1) ?: 1L
            val event = AgentEvent(
                id = id,
                userId = userId,
                chatId = chatId,
                executionId = executionId,
                seq = nextSeq,
                type = type,
                payload = payload,
                createdAt = createdAt,
            )
            mapper.appendJsonValue(
                target = layout.eventsFile(userId, chatId),
                value = event.toStored(),
            )
            event
        }

    override suspend fun get(userId: String, eventId: UUID): AgentEvent? =
        withFileLock { loadAllEvents(userId).firstOrNull { it.id == eventId } }

    override suspend fun listByChat(
        userId: String,
        chatId: UUID,
        afterSeq: Long?,
        limit: Int,
    ): List<AgentEvent> =
        withFileLock {
            loadEvents(userId, chatId)
                .filter { event -> afterSeq == null || event.seq > afterSeq }
                .take(limit)
        }

    private fun loadEvents(userId: String, chatId: UUID): List<AgentEvent> =
        mapper.readJsonLines<StoredAgentEvent>(layout.eventsFile(userId, chatId))
            .map(StoredAgentEvent::toDomain)
            .sortedBy { it.seq }

    private fun loadAllEvents(userId: String): List<AgentEvent> =
        mapper.readJsonLinesFromChatDirectories<StoredAgentEvent>(layout, userId, "events.jsonl")
            .map(StoredAgentEvent::toDomain)
            .sortedBy { it.seq }
}
