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
    // Internal durable position used to discard progress overtaken by catch-up; never sent on the wire.
    val discardAfterSeq: Long? = null,
) : AgentEventEnvelope {
    override val seq: Long? = null
    override val durable: Boolean = false
}
