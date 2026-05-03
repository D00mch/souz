package ru.souz.backend.events.model

import java.time.Instant
import java.util.UUID

sealed interface AgentEventEnvelope {
    val id: UUID
    val userId: String
    val chatId: UUID
    val executionId: UUID?
    val seq: Long?
    val type: AgentEventType
    val payload: AgentEventPayload
    val createdAt: Instant
    val durable: Boolean
}
