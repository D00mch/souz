package ru.souz.backend.chat.model

import java.time.Instant
import java.util.UUID

data class Chat(
    val id: UUID,
    val userId: String,
    val title: String?,
    val archived: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)
