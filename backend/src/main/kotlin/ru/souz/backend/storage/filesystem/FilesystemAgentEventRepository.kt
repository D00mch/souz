package ru.souz.backend.storage.filesystem

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.backend.events.model.AgentEvent
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.events.repository.AgentEventRepository

class FilesystemAgentEventRepository(
    dataDir: java.nio.file.Path,
    private val mapper: ObjectMapper = filesystemStorageObjectMapper(),
) : AgentEventRepository {
    private val mutex = Mutex()
    private val layout = FilesystemStorageLayout(dataDir)

    override suspend fun append(
        userId: String,
        chatId: UUID,
        executionId: UUID?,
        type: AgentEventType,
        payload: Map<String, String>,
        id: UUID,
        createdAt: Instant,
    ): AgentEvent = mutex.withLock {
        filesystemIo {
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
            appendJsonLine(
                target = layout.eventsFile(userId, chatId),
                line = mapper.writeValueAsString(event.toStored()),
            )
            event
        }
    }

    override suspend fun get(userId: String, eventId: UUID): AgentEvent? = mutex.withLock {
        filesystemIo {
            loadAllEvents(userId).firstOrNull { it.id == eventId }
        }
    }

    override suspend fun listByChat(
        userId: String,
        chatId: UUID,
        afterSeq: Long?,
        limit: Int,
    ): List<AgentEvent> = mutex.withLock {
        filesystemIo {
            loadEvents(userId, chatId)
                .filter { event -> afterSeq == null || event.seq > afterSeq }
                .take(limit)
        }
    }

    private fun loadEvents(userId: String, chatId: UUID): List<AgentEvent> =
        readLinesIfExists(layout.eventsFile(userId, chatId))
            .map { mapper.readValue<StoredAgentEvent>(it).toDomain() }
            .sortedBy { it.seq }

    private fun loadAllEvents(userId: String): List<AgentEvent> =
        layout.chatDirectories(userId)
            .flatMap { chatDirectory ->
                readLinesIfExists(chatDirectory.resolve("events.jsonl"))
                    .map { mapper.readValue<StoredAgentEvent>(it).toDomain() }
            }
            .sortedBy { it.seq }
}
