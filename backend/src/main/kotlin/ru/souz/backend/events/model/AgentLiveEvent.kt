package ru.souz.backend.events.model

import java.time.Instant
import java.util.UUID

data class AgentLiveEvent(
    override val id: UUID,
    override val userId: String,
    override val chatId: UUID,
    override val executionId: UUID?,
    override val type: AgentEventType,
    override val payload: AgentEventPayload,
    override val createdAt: Instant,
) : AgentEventEnvelope {
    override val seq: Long? = null
    override val durable: Boolean = false
}
