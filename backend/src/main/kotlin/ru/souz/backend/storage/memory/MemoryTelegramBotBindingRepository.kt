package ru.souz.backend.storage.memory

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import ru.souz.backend.telegram.TelegramBotBinding
import ru.souz.backend.telegram.TelegramBotBindingRepository
import ru.souz.backend.telegram.TelegramBotTokenHashConflictException
import ru.souz.backend.telegram.TelegramUserClaimResult

class MemoryTelegramBotBindingRepository(
    private val maxEntries: Int,
) : TelegramBotBindingRepository {
    private val bindingsByChat = ConcurrentHashMap<UUID, TelegramBotBinding>()
    private val chatByTokenHash = ConcurrentHashMap<String, UUID>()
    private val creationOrder = ConcurrentLinkedQueue<UUID>()

    constructor() : this(DEFAULT_MEMORY_REPOSITORY_MAX_ENTRIES)

    override suspend fun getByChat(chatId: UUID): TelegramBotBinding? = bindingsByChat[chatId]

    override suspend fun getByUserAndChat(
        userId: String,
        chatId: UUID,
    ): TelegramBotBinding? = bindingsByChat[chatId]?.takeIf { it.userId == userId }

    override suspend fun findByTokenHash(botTokenHash: String): TelegramBotBinding? =
        chatByTokenHash[botTokenHash]?.let(bindingsByChat::get)

    override suspend fun listEnabled(): List<TelegramBotBinding> =
        bindingsByChat.values
            .asSequence()
            .filter { it.enabled }
            .sortedByDescending { it.updatedAt }
            .toList()

    override suspend fun upsertForChat(
        userId: String,
        chatId: UUID,
        botToken: String,
        botTokenHash: String,
        linkSecretHash: String,
        botUsername: String?,
        botFirstName: String?,
        now: Instant,
    ): TelegramBotBinding {
        reserveTokenHash(chatId, botTokenHash)
        var created = false
        var previousTokenHash: String? = null
        val updated = bindingsByChat.compute(chatId) { _, current ->
            previousTokenHash = current?.botTokenHash
            if (current == null) {
                created = true
                TelegramBotBinding(
                    id = UUID.randomUUID(),
                    userId = userId,
                    chatId = chatId,
                    botTokenEncrypted = botToken,
                    botTokenHash = botTokenHash,
                    linkSecretHash = linkSecretHash,
                    botUsername = botUsername,
                    botFirstName = botFirstName,
                    lastUpdateId = 0L,
                    enabled = true,
                    telegramUserId = null,
                    telegramChatId = null,
                    telegramUsername = null,
                    telegramFirstName = null,
                    telegramLastName = null,
                    linkedAt = null,
                    pollerOwner = null,
                    pollerLeaseUntil = null,
                    lastError = null,
                    lastErrorAt = null,
                    createdAt = now,
                    updatedAt = now,
                )
            } else {
                current.copy(
                    userId = userId,
                    botTokenEncrypted = botToken,
                    botTokenHash = botTokenHash,
                    linkSecretHash = linkSecretHash,
                    botUsername = botUsername,
                    botFirstName = botFirstName,
                    lastUpdateId = 0L,
                    enabled = true,
                    telegramUserId = null,
                    telegramChatId = null,
                    telegramUsername = null,
                    telegramFirstName = null,
                    telegramLastName = null,
                    linkedAt = null,
                    pollerOwner = null,
                    pollerLeaseUntil = null,
                    lastError = null,
                    lastErrorAt = null,
                    updatedAt = now,
                )
            }
        } ?: error("Telegram binding compute must not return null.")

        previousTokenHash
            ?.takeIf { it != botTokenHash }
            ?.let { chatByTokenHash.remove(it, chatId) }
        if (created) {
            creationOrder.add(chatId)
            evictIfNeeded()
        }
        return updated
    }

    override suspend fun deleteByChat(chatId: UUID) {
        val removed = bindingsByChat.remove(chatId) ?: return
        chatByTokenHash.remove(removed.botTokenHash, chatId)
    }

    override suspend fun claimTelegramUser(
        id: UUID,
        linkSecretHash: String,
        telegramUserId: Long,
        telegramChatId: Long,
        telegramUsername: String?,
        telegramFirstName: String?,
        telegramLastName: String?,
        linkedAt: Instant,
        updatedAt: Instant,
    ): TelegramUserClaimResult {
        val chatId = findChatIdByBindingId(id) ?: return TelegramUserClaimResult.NotFound
        var result: TelegramUserClaimResult = TelegramUserClaimResult.NotFound
        bindingsByChat.computeIfPresent(chatId) { _, current ->
            result = when {
                current.linked -> TelegramUserClaimResult.AlreadyLinked(current)
                current.linkSecretHash != linkSecretHash -> TelegramUserClaimResult.InvalidSecret(current)
                else -> {
                    val updated = current.copy(
                        linkSecretHash = null,
                        telegramUserId = telegramUserId,
                        telegramChatId = telegramChatId,
                        telegramUsername = telegramUsername,
                        telegramFirstName = telegramFirstName,
                        telegramLastName = telegramLastName,
                        linkedAt = linkedAt,
                        updatedAt = updatedAt,
                    )
                    TelegramUserClaimResult.Claimed(updated)
                }
            }
            when (val currentResult = result) {
                is TelegramUserClaimResult.Claimed -> currentResult.binding
                is TelegramUserClaimResult.AlreadyLinked -> currentResult.binding
                is TelegramUserClaimResult.InvalidSecret -> currentResult.binding
                TelegramUserClaimResult.NotFound -> current
            }
        }
        return result
    }

    override suspend fun tryAcquireLease(
        id: UUID,
        owner: String,
        leaseUntil: Instant,
        now: Instant,
    ): TelegramBotBinding? {
        val chatId = findChatIdByBindingId(id) ?: return null
        var leased: TelegramBotBinding? = null
        bindingsByChat.computeIfPresent(chatId) { _, current ->
            val canAcquire = current.enabled &&
                (current.pollerLeaseUntil == null || current.pollerLeaseUntil < now || current.pollerOwner == owner)
            if (!canAcquire) {
                return@computeIfPresent current
            }
            current.copy(
                pollerOwner = owner,
                pollerLeaseUntil = leaseUntil,
            ).also { leased = it }
        }
        return leased
    }

    override suspend fun hasActiveLease(
        id: UUID,
        owner: String,
        now: Instant,
    ): Boolean {
        val current = findByBindingId(id) ?: return false
        val leaseUntil = current.pollerLeaseUntil ?: return false
        return current.pollerOwner == owner && leaseUntil >= now
    }

    override suspend fun updateLastUpdateId(
        id: UUID,
        lastUpdateId: Long,
        updatedAt: Instant,
        owner: String?,
    ) {
        val chatId = findChatIdByBindingId(id) ?: return
        bindingsByChat.computeIfPresent(chatId) { _, current ->
            if (owner != null && current.pollerOwner != owner) {
                return@computeIfPresent current
            }
            current.copy(
                lastUpdateId = maxOf(current.lastUpdateId, lastUpdateId),
                updatedAt = updatedAt,
            )
        }
    }

    override suspend fun markError(
        id: UUID,
        lastError: String,
        lastErrorAt: Instant,
        disable: Boolean,
    ) {
        val chatId = findChatIdByBindingId(id) ?: return
        bindingsByChat.computeIfPresent(chatId) { _, current ->
            current.copy(
                enabled = if (disable) false else current.enabled,
                lastError = lastError,
                lastErrorAt = lastErrorAt,
                updatedAt = lastErrorAt,
            )
        }
    }

    override suspend fun clearError(
        id: UUID,
        updatedAt: Instant,
    ) {
        val chatId = findChatIdByBindingId(id) ?: return
        bindingsByChat.computeIfPresent(chatId) { _, current ->
            current.copy(
                lastError = null,
                lastErrorAt = null,
                updatedAt = updatedAt,
            )
        }
    }

    private fun reserveTokenHash(
        chatId: UUID,
        botTokenHash: String,
    ) {
        while (true) {
            val existingChatId = chatByTokenHash.putIfAbsent(botTokenHash, chatId)
            if (existingChatId == null || existingChatId == chatId) {
                return
            }
            val existingBinding = bindingsByChat[existingChatId]
            if (existingBinding == null || existingBinding.botTokenHash != botTokenHash) {
                chatByTokenHash.remove(botTokenHash, existingChatId)
                continue
            }
            throw TelegramBotTokenHashConflictException()
        }
    }

    private fun evictIfNeeded() {
        while (bindingsByChat.size > maxEntries) {
            val oldestChatId = creationOrder.poll() ?: return
            val removed = bindingsByChat.remove(oldestChatId) ?: continue
            chatByTokenHash.remove(removed.botTokenHash, oldestChatId)
        }
    }

    private fun findByBindingId(id: UUID): TelegramBotBinding? =
        bindingsByChat.values.firstOrNull { it.id == id }

    private fun findChatIdByBindingId(id: UUID): UUID? =
        bindingsByChat.entries.firstOrNull { it.value.id == id }?.key
}
