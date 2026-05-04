package ru.souz.backend.storage.filesystem

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import ru.souz.backend.telegram.TelegramBotBinding
import ru.souz.backend.telegram.TelegramBotBindingRepository
import ru.souz.backend.telegram.TelegramBotTokenHashConflictException

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

    override suspend fun linkTelegramUser(
        id: UUID,
        telegramUserId: Long,
        telegramChatId: Long,
        telegramUsername: String?,
        telegramFirstName: String?,
        telegramLastName: String?,
        linkedAt: Instant,
        updatedAt: Instant,
    ): TelegramBotBinding? = withFileLock {
        val current = readAllBindings().firstOrNull { it.id == id } ?: return@withFileLock null
        val updated = current.copy(
            telegramUserId = current.telegramUserId ?: telegramUserId,
            telegramChatId = current.telegramChatId ?: telegramChatId,
            telegramUsername = current.telegramUsername ?: telegramUsername,
            telegramFirstName = current.telegramFirstName ?: telegramFirstName,
            telegramLastName = current.telegramLastName ?: telegramLastName,
            linkedAt = current.linkedAt ?: linkedAt,
            updatedAt = updatedAt,
        )
        mapper.writeJsonFile(layout.telegramBotBindingFile(current.userId, current.chatId), updated.toStored())
        updated
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

    override suspend fun updateLastUpdateId(
        id: UUID,
        lastUpdateId: Long,
        updatedAt: Instant,
    ) = withFileLock {
        val current = readAllBindings().firstOrNull { it.id == id } ?: return@withFileLock
        mapper.writeJsonFile(
            layout.telegramBotBindingFile(current.userId, current.chatId),
            current.copy(
                lastUpdateId = lastUpdateId,
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
