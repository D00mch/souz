package ru.souz.backend.storage.filesystem

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import ru.souz.backend.telegram.TelegramBotBinding
import ru.souz.backend.telegram.TelegramBotBindingRepository
import ru.souz.backend.telegram.TelegramBotTokenHashConflictException
import ru.souz.backend.telegram.TelegramUserClaimResult

class FilesystemTelegramBotBindingRepository(
    dataDir: java.nio.file.Path,
    mapper: ObjectMapper = filesystemStorageObjectMapper(),
) : BaseFilesystemRepository(dataDir, mapper), TelegramBotBindingRepository {

    override suspend fun getByChat(chatId: UUID): TelegramBotBinding? =
        withFileLock {
            readAllBindings().firstOrNull { it.chatId == chatId }
        }

    override suspend fun getByUserAndChat(
        userId: String,
        chatId: UUID,
    ): TelegramBotBinding? =
        withFileLock {
            mapper.readJsonIfExists<StoredTelegramBotBinding>(layout.telegramBotBindingFile(userId, chatId))
                ?.toDomain()
        }

    override suspend fun findByTokenHash(botTokenHash: String): TelegramBotBinding? =
        withFileLock {
            readAllBindings().firstOrNull { it.botTokenHash == botTokenHash }
        }

    override suspend fun listEnabled(): List<TelegramBotBinding> =
        withFileLock {
            readAllBindings()
                .filter { it.enabled }
                .sortedByDescending { it.updatedAt }
        }

    override suspend fun upsertForChat(
        userId: String,
        chatId: UUID,
        botToken: String,
        botTokenHash: String,
        linkSecretHash: String,
        botUsername: String?,
        botFirstName: String?,
        now: Instant,
    ): TelegramBotBinding =
        withFileLock {
            val existingByTokenHash = readAllBindings()
                .firstOrNull { it.botTokenHash == botTokenHash }
            if (existingByTokenHash != null && existingByTokenHash.chatId != chatId) {
                throw TelegramBotTokenHashConflictException()
            }

            val file = layout.telegramBotBindingFile(userId, chatId)
            val current = mapper.readJsonIfExists<StoredTelegramBotBinding>(file)?.toDomain()
            val updated = if (current == null) {
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
            mapper.writeJsonFile(file, updated.toStored())
            updated
        }

    override suspend fun deleteByChat(chatId: UUID) =
        withFileLock {
            val binding = readAllBindings().firstOrNull { it.chatId == chatId } ?: return@withFileLock
            Files.deleteIfExists(layout.telegramBotBindingFile(binding.userId, binding.chatId))
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
    ): TelegramUserClaimResult = withFileLock {
        val current = readAllBindings().firstOrNull { it.id == id } ?: return@withFileLock TelegramUserClaimResult.NotFound
        if (current.linked) {
            return@withFileLock TelegramUserClaimResult.AlreadyLinked(current)
        }
        if (current.linkSecretHash != linkSecretHash) {
            return@withFileLock TelegramUserClaimResult.InvalidSecret(current)
        }
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
        mapper.writeJsonFile(layout.telegramBotBindingFile(current.userId, current.chatId), updated.toStored())
        TelegramUserClaimResult.Claimed(updated)
    }

    override suspend fun tryAcquireLease(
        id: UUID,
        owner: String,
        leaseUntil: Instant,
        now: Instant,
    ): TelegramBotBinding? = withFileLock {
        val current = readAllBindings().firstOrNull { it.id == id } ?: return@withFileLock null
        val canAcquire = current.enabled &&
            (current.pollerLeaseUntil == null || current.pollerLeaseUntil < now || current.pollerOwner == owner)
        if (!canAcquire) {
            return@withFileLock null
        }
        val updated = current.copy(
            pollerOwner = owner,
            pollerLeaseUntil = leaseUntil,
        )
        mapper.writeJsonFile(layout.telegramBotBindingFile(current.userId, current.chatId), updated.toStored())
        updated
    }

    override suspend fun hasActiveLease(
        id: UUID,
        owner: String,
        now: Instant,
    ): Boolean = withFileLock {
        val current = readAllBindings().firstOrNull { it.id == id } ?: return@withFileLock false
        val leaseUntil = current.pollerLeaseUntil ?: return@withFileLock false
        current.pollerOwner == owner && leaseUntil >= now
    }

    override suspend fun updateLastUpdateId(
        id: UUID,
        lastUpdateId: Long,
        updatedAt: Instant,
        owner: String?,
    ) = withFileLock {
        val current = readAllBindings().firstOrNull { it.id == id } ?: return@withFileLock
        if (owner != null && current.pollerOwner != owner) {
            return@withFileLock
        }
        mapper.writeJsonFile(
            layout.telegramBotBindingFile(current.userId, current.chatId),
            current.copy(
                lastUpdateId = maxOf(current.lastUpdateId, lastUpdateId),
                updatedAt = updatedAt,
            ).toStored(),
        )
    }

    override suspend fun markError(
        id: UUID,
        lastError: String,
        lastErrorAt: Instant,
        disable: Boolean,
    ) = withFileLock {
        val current = readAllBindings().firstOrNull { it.id == id } ?: return@withFileLock
        mapper.writeJsonFile(
            layout.telegramBotBindingFile(current.userId, current.chatId),
            current.copy(
                enabled = if (disable) false else current.enabled,
                lastError = lastError,
                lastErrorAt = lastErrorAt,
                updatedAt = lastErrorAt,
            ).toStored(),
        )
    }

    override suspend fun clearError(
        id: UUID,
        updatedAt: Instant,
    ) = withFileLock {
        val current = readAllBindings().firstOrNull { it.id == id } ?: return@withFileLock
        mapper.writeJsonFile(
            layout.telegramBotBindingFile(current.userId, current.chatId),
            current.copy(
                lastError = null,
                lastErrorAt = null,
                updatedAt = updatedAt,
            ).toStored(),
        )
    }

    private fun readAllBindings(): List<TelegramBotBinding> =
        layout.allChatDirectories()
            .mapNotNull { chatDirectory ->
                mapper.readJsonIfExists<StoredTelegramBotBinding>(
                    chatDirectory.resolve(FilesystemStorageLayout.TELEGRAM_BOT_BINDING_FILE_NAME)
                )?.toDomain()
            }
}
