package ru.souz.backend.storage.filesystem

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.chat.repository.ChatRepository

class FilesystemChatRepository(
    dataDir: java.nio.file.Path,
    private val mapper: ObjectMapper = filesystemStorageObjectMapper(),
) : ChatRepository {
    private val mutex = Mutex()
    private val layout = FilesystemStorageLayout(dataDir)

    override suspend fun create(chat: Chat): Chat = mutex.withLock {
        filesystemIo {
            writeAtomicString(
                target = layout.chatFile(chat.userId, chat.id),
                content = mapper.writeValueAsString(chat.toStored()),
            )
            chat
        }
    }

    override suspend fun get(userId: String, chatId: UUID): Chat? = mutex.withLock {
        filesystemIo {
            readTextIfExists(layout.chatFile(userId, chatId))
                ?.let { mapper.readValue<StoredChat>(it).toDomain() }
        }
    }

    override suspend fun list(
        userId: String,
        limit: Int,
        includeArchived: Boolean,
    ): List<Chat> = mutex.withLock {
        filesystemIo {
            layout.chatDirectories(userId)
                .mapNotNull { chatDirectory ->
                    readTextIfExists(chatDirectory.resolve("chat.json"))
                        ?.let { mapper.readValue<StoredChat>(it).toDomain() }
                }
                .filter { includeArchived || !it.archived }
                .sortedByDescending { it.updatedAt }
                .take(limit)
        }
    }

    override suspend fun update(chat: Chat): Chat = create(chat)
}
