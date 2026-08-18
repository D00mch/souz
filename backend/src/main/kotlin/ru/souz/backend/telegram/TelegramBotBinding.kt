package ru.souz.backend.telegram

import java.time.Instant
import java.util.UUID

data class TelegramBotBinding(
    val id: UUID,
    val userId: String,
    val chatId: UUID,
    val botTokenEncrypted: String,
    val botTokenHash: String,
    val linkSecretHash: String?,
    val botUsername: String?,
    val botFirstName: String?,
    val lastUpdateId: Long,
    val enabled: Boolean,
    val telegramUserId: Long?,
    val telegramChatId: Long?,
    val telegramUsername: String?,
    val telegramFirstName: String?,
    val telegramLastName: String?,
    val linkedAt: Instant?,
    val pollerOwner: String?,
    val pollerLeaseUntil: Instant?,
    val lastError: String?,
    val lastErrorAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val linked: Boolean
        get() = telegramUserId != null && telegramChatId != null

    /**
     * Enabled and fully linked — the single "is this a live, usable Telegram channel" check, shared
     * by [TelegramChannelProvider][ru.souz.backend.channels.TelegramChannelProvider]'s own listing/
     * sending and the ownership-claim check in `BackendDiModule` so the two can't silently disagree
     * about which chats belong to Telegram.
     */
    val active: Boolean
        get() = enabled && linked
}

sealed interface TelegramUserClaimResult {
    data class Claimed(
        val binding: TelegramBotBinding,
    ) : TelegramUserClaimResult

    data class AlreadyLinked(
        val binding: TelegramBotBinding,
    ) : TelegramUserClaimResult

    data class InvalidSecret(
        val binding: TelegramBotBinding,
    ) : TelegramUserClaimResult

    data object NotFound : TelegramUserClaimResult
}

internal fun sha256Hex(value: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
