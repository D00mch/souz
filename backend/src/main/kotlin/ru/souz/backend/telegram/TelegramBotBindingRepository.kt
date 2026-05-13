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
        linkSecretHash: String,
        botUsername: String? = null,
        botFirstName: String? = null,
        now: Instant,
    ): TelegramBotBinding

    suspend fun deleteByChat(chatId: UUID)

    suspend fun claimTelegramUser(
        id: UUID,
        linkSecretHash: String,
        telegramUserId: Long,
        telegramChatId: Long,
        telegramUsername: String?,
        telegramFirstName: String?,
        telegramLastName: String?,
        linkedAt: Instant,
        updatedAt: Instant = linkedAt,
    ): TelegramUserClaimResult

    suspend fun tryAcquireLease(
        id: UUID,
        owner: String,
        leaseUntil: Instant,
        now: Instant = Instant.now(),
    ): TelegramBotBinding?

    suspend fun hasActiveLease(
        id: UUID,
        owner: String,
        now: Instant = Instant.now(),
    ): Boolean

    suspend fun updateLastUpdateId(
        id: UUID,
        lastUpdateId: Long,
        updatedAt: Instant = Instant.now(),
        owner: String? = null,
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
