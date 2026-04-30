package ru.souz.backend.events.model

import java.time.Instant
import java.util.UUID

data class AgentEvent(
    val id: UUID,
    val userId: String,
    val chatId: UUID,
    val executionId: UUID?,
    val seq: Long,
    val type: AgentEventType,
    val payload: Map<String, String>,
    val createdAt: Instant,
)
