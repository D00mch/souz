package ru.souz.backend.storage.memory

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.backend.events.model.AgentEvent
import ru.souz.backend.events.model.AgentEventPayload
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.events.repository.AgentEventRepository

class MemoryAgentEventRepository(
    maxEntries: Int,
) : AgentEventRepository {
    private val mutex = Mutex()
    private val events = boundedLruMap<EventKey, AgentEvent>(maxEntries)
    private val nextSeqByConversation = boundedLruMap<EventConversationKey, Long>(maxEntries)

    constructor() : this(DEFAULT_MEMORY_REPOSITORY_MAX_ENTRIES)

    override suspend fun append(
        userId: String,
        chatId: UUID,
        executionId: UUID?,
        type: AgentEventType,
        payload: AgentEventPayload,
        id: UUID,
        createdAt: Instant,
    ): AgentEvent = mutex.withLock {
        val conversationKey = EventConversationKey(userId, chatId)
        val nextSeq = (nextSeqByConversation[conversationKey] ?: 0L) + 1L
        nextSeqByConversation[conversationKey] = nextSeq
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
        events[EventKey(userId, id)] = event
        event
    }

    override suspend fun get(userId: String, eventId: UUID): AgentEvent? = mutex.withLock {
        events[EventKey(userId, eventId)]
    }

    override suspend fun listByChat(
        userId: String,
        chatId: UUID,
        afterSeq: Long?,
        limit: Int,
    ): List<AgentEvent> = mutex.withLock {
        events.values
            .asSequence()
            .filter { event -> event.userId == userId && event.chatId == chatId }
            .filter { event -> afterSeq == null || event.seq > afterSeq }
            .sortedBy { it.seq }
            .take(limit)
            .toList()
    }
}

private data class EventConversationKey(
    val userId: String,
    val chatId: UUID,
)

private data class EventKey(
    val userId: String,
    val eventId: UUID,
)
