package ru.souz.backend.events.model

import java.time.Instant
import java.util.UUID

data class AgentEvent(
    override val id: UUID,
    override val userId: String,
    override val chatId: UUID,
    override val executionId: UUID?,
    override val seq: Long,
    override val type: AgentEventType,
    override val payload: AgentEventPayload,
    override val createdAt: Instant,
) : AgentEventEnvelope {
    override val durable: Boolean = true
}
