package ru.souz.backend.telegram

import java.time.Instant
import java.util.UUID

data class TelegramBotBinding(
    val id: UUID,
    val userId: String,
    val chatId: UUID,
    val botToken: String,
    val botTokenHash: String,
    val lastUpdateId: Long,
    val enabled: Boolean,
    val lastError: String?,
    val lastErrorAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

internal fun sha256Hex(value: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
