package ru.souz.backend.storage.memory

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.backend.telegram.TelegramBotBinding
import ru.souz.backend.telegram.TelegramBotBindingRepository
import ru.souz.backend.telegram.TelegramBotTokenHashConflictException

class MemoryTelegramBotBindingRepository(
    maxEntries: Int,
) : TelegramBotBindingRepository {
    private val mutex = Mutex()
    private val bindingsByChat = HashMap<UUID, UUID>()
    private val bindingsByTokenHash = HashMap<String, UUID>()
    private val bindings = boundedLruMap<UUID, TelegramBotBinding>(maxEntries) { _, binding ->
        bindingsByChat.remove(binding.chatId)
        bindingsByTokenHash.remove(binding.botTokenHash)
    }

    constructor() : this(DEFAULT_MEMORY_REPOSITORY_MAX_ENTRIES)

    override suspend fun getByChat(chatId: UUID): TelegramBotBinding? = mutex.withLock {
        bindingsByChat[chatId]?.let(bindings::get)
    }

    override suspend fun getByUserAndChat(
        userId: String,
        chatId: UUID,
    ): TelegramBotBinding? = mutex.withLock {
        bindingsByChat[chatId]
            ?.let(bindings::get)
            ?.takeIf { it.userId == userId }
    }

    override suspend fun findByTokenHash(botTokenHash: String): TelegramBotBinding? = mutex.withLock {
        bindingsByTokenHash[botTokenHash]?.let(bindings::get)
    }

    override suspend fun listEnabled(): List<TelegramBotBinding> = mutex.withLock {
        bindings.values
            .asSequence()
            .filter { it.enabled }
            .sortedByDescending { it.updatedAt }
            .toList()
    }

    override suspend fun upsertForChat(
        userId: String,
        chatId: UUID,
        botToken: String,
        botTokenHash: String,
        botUsername: String?,
        botFirstName: String?,
        now: Instant,
    ): TelegramBotBinding = mutex.withLock {
        val existingByTokenHash = bindingsByTokenHash[botTokenHash]?.let(bindings::get)
        if (existingByTokenHash != null && existingByTokenHash.chatId != chatId) {
            throw TelegramBotTokenHashConflictException()
        }
        val existing = bindingsByChat[chatId]?.let(bindings::get)
        val updated = if (existing == null) {
            TelegramBotBinding(
                id = UUID.randomUUID(),
                userId = userId,
                chatId = chatId,
                botTokenEncrypted = botToken,
                botTokenHash = botTokenHash,
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
            if (existing.botTokenHash != botTokenHash) {
                bindingsByTokenHash.remove(existing.botTokenHash)
            }
            existing.copy(
                userId = userId,
                botTokenEncrypted = botToken,
                botTokenHash = botTokenHash,
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
        store(updated)
    }

    override suspend fun deleteByChat(chatId: UUID) = mutex.withLock {
        val bindingId = bindingsByChat.remove(chatId) ?: return@withLock
        val binding = bindings.remove(bindingId) ?: return@withLock
        bindingsByTokenHash.remove(binding.botTokenHash)
    }

    override suspend fun linkTelegramUser(
        id: UUID,
        telegramUserId: Long,
        telegramChatId: Long,
        telegramUsername: String?,
        telegramFirstName: String?,
        telegramLastName: String?,
        linkedAt: Instant,
        updatedAt: Instant,
    ): TelegramBotBinding? = mutex.withLock {
        val current = bindings[id] ?: return@withLock null
        val updated = current.copy(
            telegramUserId = current.telegramUserId ?: telegramUserId,
            telegramChatId = current.telegramChatId ?: telegramChatId,
            telegramUsername = current.telegramUsername ?: telegramUsername,
            telegramFirstName = current.telegramFirstName ?: telegramFirstName,
            telegramLastName = current.telegramLastName ?: telegramLastName,
            linkedAt = current.linkedAt ?: linkedAt,
            updatedAt = updatedAt,
        )
        store(updated)
    }

    override suspend fun tryAcquireLease(
        id: UUID,
        owner: String,
        leaseUntil: Instant,
        now: Instant,
    ): TelegramBotBinding? = mutex.withLock {
        val current = bindings[id] ?: return@withLock null
        val canAcquire = current.enabled &&
            (current.pollerLeaseUntil == null || current.pollerLeaseUntil < now || current.pollerOwner == owner)
        if (!canAcquire) {
            return@withLock null
        }
        store(
            current.copy(
                pollerOwner = owner,
                pollerLeaseUntil = leaseUntil,
            )
        )
    }

    override suspend fun updateLastUpdateId(
        id: UUID,
        lastUpdateId: Long,
        updatedAt: Instant,
    ) = mutex.withLock {
        val current = bindings[id] ?: return@withLock
        store(
            current.copy(
                lastUpdateId = lastUpdateId,
                updatedAt = updatedAt,
            )
        )
    }

    override suspend fun markError(
        id: UUID,
        lastError: String,
        lastErrorAt: Instant,
        disable: Boolean,
    ) = mutex.withLock {
        val current = bindings[id] ?: return@withLock
        store(
            current.copy(
                enabled = if (disable) false else current.enabled,
                lastError = lastError,
                lastErrorAt = lastErrorAt,
                updatedAt = lastErrorAt,
            )
        )
    }

    override suspend fun clearError(
        id: UUID,
        updatedAt: Instant,
    ) = mutex.withLock {
        val current = bindings[id] ?: return@withLock
        store(
            current.copy(
                lastError = null,
                lastErrorAt = null,
                updatedAt = updatedAt,
            )
        )
    }

    private fun store(binding: TelegramBotBinding): TelegramBotBinding {
        bindings[binding.id] = binding
        bindingsByChat[binding.chatId] = binding.id
        bindingsByTokenHash[binding.botTokenHash] = binding.id
        return binding
    }
}
