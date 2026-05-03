package ru.souz.backend.storage.filesystem

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import ru.souz.backend.chat.model.ChatMessage
import ru.souz.backend.chat.model.ChatRole
import ru.souz.backend.chat.repository.MessageRepository

class FilesystemMessageRepository(
    dataDir: java.nio.file.Path,
    mapper: ObjectMapper = filesystemStorageObjectMapper(),
) : BaseFilesystemRepository(dataDir, mapper), MessageRepository {

    override suspend fun append(
        userId: String,
        chatId: UUID,
        role: ChatRole,
        content: String,
        metadata: Map<String, String>,
        id: UUID,
        createdAt: Instant,
    ): ChatMessage =
        withFileLock {
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
            mapper.appendJsonValue(
                target = layout.messagesFile(userId, chatId),
                value = message.toStored(),
            )
            message
        }

    override suspend fun get(userId: String, chatId: UUID, seq: Long): ChatMessage? =
        withFileLock {
            loadMessages(userId, chatId).firstOrNull { it.seq == seq }
        }

    override suspend fun getById(
        userId: String,
        chatId: UUID,
        messageId: UUID,
    ): ChatMessage? =
        withFileLock {
            loadMessages(userId, chatId).firstOrNull { it.id == messageId }
        }

    override suspend fun latest(userId: String, chatId: UUID): ChatMessage? =
        withFileLock { loadMessages(userId, chatId).lastOrNull() }

    override suspend fun updateContent(
        userId: String,
        chatId: UUID,
        messageId: UUID,
        content: String,
    ): ChatMessage? =
        withFileLock {
            val current = loadMessages(userId, chatId).firstOrNull { it.id == messageId } ?: return@withFileLock null
            val updated = current.copy(content = content)
            mapper.appendJsonValue(
                target = layout.messagesFile(userId, chatId),
                value = updated.toStored(),
            )
            updated
        }

    override suspend fun list(
        userId: String,
        chatId: UUID,
        afterSeq: Long?,
        beforeSeq: Long?,
        limit: Int,
    ): List<ChatMessage> =
        withFileLock {
            val filtered = loadMessages(userId, chatId)
                .filter { message -> afterSeq == null || message.seq > afterSeq }
                .filter { message -> beforeSeq == null || message.seq < beforeSeq }
            when {
                beforeSeq != null -> filtered.takeLast(limit)
                else -> filtered.take(limit)
            }
        }

    private fun loadMessages(userId: String, chatId: UUID): List<ChatMessage> =
        mapper.readJsonLines<StoredChatMessage>(layout.messagesFile(userId, chatId))
            .map(StoredChatMessage::toDomain)
            .associateBy { it.id }
            .values
            .sortedBy { it.seq }
}
