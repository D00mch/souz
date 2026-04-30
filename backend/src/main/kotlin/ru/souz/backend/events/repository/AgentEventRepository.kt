package ru.souz.backend.events.repository

import java.time.Instant
import java.util.UUID
import ru.souz.backend.events.model.AgentEvent
import ru.souz.backend.events.model.AgentEventType

interface AgentEventRepository {
    suspend fun append(
        userId: String,
        chatId: UUID,
        executionId: UUID?,
        type: AgentEventType,
        payload: Map<String, String>,
        id: UUID = UUID.randomUUID(),
        createdAt: Instant = Instant.now(),
    ): AgentEvent

    suspend fun get(userId: String, eventId: UUID): AgentEvent?

    suspend fun listByChat(
        userId: String,
        chatId: UUID,
        afterSeq: Long? = null,
        limit: Int = DEFAULT_LIMIT,
    ): List<AgentEvent>

    companion object {
        const val DEFAULT_LIMIT: Int = 100
    }
}
