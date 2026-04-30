package ru.souz.backend.storage.memory

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.backend.events.model.AgentEvent
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.events.repository.AgentEventRepository

class MemoryAgentEventRepository : AgentEventRepository {
    private val mutex = Mutex()
    private val events = LinkedHashMap<EventConversationKey, MutableList<AgentEvent>>()
    private val eventsById = LinkedHashMap<EventKey, AgentEvent>()

    override suspend fun append(
        userId: String,
        chatId: UUID,
        executionId: UUID?,
        type: AgentEventType,
        payload: Map<String, String>,
        id: UUID,
        createdAt: Instant,
    ): AgentEvent = mutex.withLock {
        val key = EventConversationKey(userId, chatId)
        val nextSeq = events[key]?.lastOrNull()?.seq?.plus(1) ?: 1L
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
        events.getOrPut(key) { ArrayList() } += event
        eventsById[EventKey(userId, id)] = event
        event
    }

    override suspend fun get(userId: String, eventId: UUID): AgentEvent? = mutex.withLock {
        eventsById[EventKey(userId, eventId)]
    }

    override suspend fun listByChat(
        userId: String,
        chatId: UUID,
        afterSeq: Long?,
        limit: Int,
    ): List<AgentEvent> = mutex.withLock {
        events[EventConversationKey(userId, chatId)]
            .orEmpty()
            .asSequence()
            .filter { event -> afterSeq == null || event.seq > afterSeq }
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
