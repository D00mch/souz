package ru.souz.backend.storage.filesystem

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.chat.repository.ChatRepository

class FilesystemChatRepository(
    dataDir: java.nio.file.Path,
    mapper: ObjectMapper = filesystemStorageObjectMapper(),
) : BaseFilesystemRepository(dataDir, mapper), ChatRepository {

    override suspend fun create(chat: Chat): Chat =
        withFileLock {
            mapper.writeJsonFile(
                target = layout.chatFile(chat.userId, chat.id),
                value = chat.toStored(),
            )
            chat
        }

    override suspend fun get(userId: String, chatId: UUID): Chat? =
        withFileLock {
            mapper.readJsonIfExists<StoredChat>(layout.chatFile(userId, chatId))?.toDomain()
        }

    override suspend fun list(
        userId: String,
        limit: Int,
        includeArchived: Boolean,
    ): List<Chat> =
        withFileLock {
            layout.chatDirectories(userId)
                .mapNotNull { chatDirectory ->
                    mapper.readJsonIfExists<StoredChat>(chatDirectory.resolve("chat.json"))?.toDomain()
                }
                .filter { includeArchived || !it.archived }
                .sortedByDescending { it.updatedAt }
                .take(limit)
        }

    override suspend fun update(chat: Chat): Chat = create(chat)

    override suspend fun updateTitle(
        userId: String,
        chatId: UUID,
        title: String,
        updatedAt: Instant,
    ): Chat? = withFileLock {
        val chatFile = layout.chatFile(userId, chatId)
        val current = mapper.readJsonIfExists<StoredChat>(chatFile)?.toDomain() ?: return@withFileLock null
        val updated = current.copy(
            title = title,
            updatedAt = updatedAt,
        )
        mapper.writeJsonFile(
            target = chatFile,
            value = updated.toStored(),
        )
        updated
    }

    override suspend fun updateArchived(
        userId: String,
        chatId: UUID,
        archived: Boolean,
        updatedAt: Instant,
    ): Chat? = withFileLock {
        val chatFile = layout.chatFile(userId, chatId)
        val current = mapper.readJsonIfExists<StoredChat>(chatFile)?.toDomain() ?: return@withFileLock null
        val updated = current.copy(
            archived = archived,
            updatedAt = updatedAt,
        )
        mapper.writeJsonFile(
            target = chatFile,
            value = updated.toStored(),
        )
        updated
    }
}
