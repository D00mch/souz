package ru.souz.backend.storage.filesystem

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.backend.chat.model.ChatMessage
import ru.souz.backend.chat.model.ChatRole
import ru.souz.backend.chat.repository.MessageRepository

class FilesystemMessageRepository(
    dataDir: java.nio.file.Path,
    private val mapper: ObjectMapper = filesystemStorageObjectMapper(),
) : MessageRepository {
    private val mutex = Mutex()
    private val layout = FilesystemStorageLayout(dataDir)

    override suspend fun append(
        userId: String,
        chatId: UUID,
        role: ChatRole,
        content: String,
        metadata: Map<String, String>,
        id: UUID,
        createdAt: Instant,
    ): ChatMessage = mutex.withLock {
        filesystemIo {
            val currentMessages = loadMessages(userId, chatId)
            val nextSeq = currentMessages.maxOfOrNull { it.seq }?.plus(1) ?: 1L
            val message = ChatMessage(
                id = id,
                userId = userId,
                chatId = chatId,
                seq = nextSeq,
                role = role,
                content = content,
                metadata = metadata,
                createdAt = createdAt,
            )
            appendJsonLine(
                target = layout.messagesFile(userId, chatId),
                line = mapper.writeValueAsString(message.toStored()),
            )
            message
        }
    }

    override suspend fun get(userId: String, chatId: UUID, seq: Long): ChatMessage? = mutex.withLock {
        filesystemIo {
            loadMessages(userId, chatId).firstOrNull { it.seq == seq }
        }
    }

    override suspend fun getById(
        userId: String,
        chatId: UUID,
        messageId: UUID,
    ): ChatMessage? = mutex.withLock {
        filesystemIo {
            loadMessages(userId, chatId).firstOrNull { it.id == messageId }
        }
    }

    override suspend fun latest(userId: String, chatId: UUID): ChatMessage? = mutex.withLock {
        filesystemIo {
            loadMessages(userId, chatId).lastOrNull()
        }
    }

    override suspend fun updateContent(
        userId: String,
        chatId: UUID,
        messageId: UUID,
        content: String,
    ): ChatMessage? = mutex.withLock {
        filesystemIo {
            val current = loadMessages(userId, chatId).firstOrNull { it.id == messageId } ?: return@filesystemIo null
            val updated = current.copy(content = content)
            appendJsonLine(
                target = layout.messagesFile(userId, chatId),
                line = mapper.writeValueAsString(updated.toStored()),
            )
            updated
        }
    }

    override suspend fun list(
        userId: String,
        chatId: UUID,
        afterSeq: Long?,
        beforeSeq: Long?,
        limit: Int,
    ): List<ChatMessage> = mutex.withLock {
        filesystemIo {
            val filtered = loadMessages(userId, chatId)
                .filter { message -> afterSeq == null || message.seq > afterSeq }
                .filter { message -> beforeSeq == null || message.seq < beforeSeq }
            when {
                beforeSeq != null -> filtered.takeLast(limit)
                else -> filtered.take(limit)
            }
        }
    }

    private fun loadMessages(userId: String, chatId: UUID): List<ChatMessage> =
        readLinesIfExists(layout.messagesFile(userId, chatId))
            .map { mapper.readValue<StoredChatMessage>(it).toDomain() }
            .associateBy { it.id }
            .values
            .sortedBy { it.seq }
}
