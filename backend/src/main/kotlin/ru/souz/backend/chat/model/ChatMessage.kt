package ru.souz.backend.chat.model

import java.time.Instant
import java.util.UUID

data class ChatMessage(
    val id: UUID,
    val userId: String,
    val chatId: UUID,
    val seq: Long,
    val role: ChatRole,
    val content: String,
    val metadata: Map<String, String>,
    val createdAt: Instant,
)
