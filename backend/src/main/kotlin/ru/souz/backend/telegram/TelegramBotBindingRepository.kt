package ru.souz.backend.telegram

import java.time.Instant
import java.util.UUID

interface TelegramBotBindingRepository {
    suspend fun getByChat(chatId: UUID): TelegramBotBinding?

    suspend fun getByUserAndChat(
        userId: String,
        chatId: UUID,
    ): TelegramBotBinding?

    suspend fun findByTokenHash(botTokenHash: String): TelegramBotBinding?

    suspend fun listEnabled(): List<TelegramBotBinding>

    suspend fun upsertForChat(
        userId: String,
        chatId: UUID,
        botToken: String,
        botTokenHash: String,
        now: Instant,
    ): TelegramBotBinding

    suspend fun deleteByChat(chatId: UUID)

    suspend fun updateLastUpdateId(
        id: UUID,
        lastUpdateId: Long,
        updatedAt: Instant = Instant.now(),
    )

    suspend fun markError(
        id: UUID,
        lastError: String,
        lastErrorAt: Instant = Instant.now(),
        disable: Boolean = false,
    )

    suspend fun clearError(
        id: UUID,
        updatedAt: Instant = Instant.now(),
    )
}

class TelegramBotTokenHashConflictException : RuntimeException("Telegram bot token hash is already bound.")
