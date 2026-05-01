package ru.souz.backend.storage.memory

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.backend.chat.model.ChatMessage
import ru.souz.backend.chat.model.ChatRole
import ru.souz.backend.chat.repository.MessageRepository

class MemoryMessageRepository : MessageRepository {
    private val mutex = Mutex()
    private val messages = LinkedHashMap<ConversationKey, MutableList<ChatMessage>>()

    override suspend fun append(
        userId: String,
        chatId: UUID,
        role: ChatRole,
        content: String,
        metadata: Map<String, String>,
        id: UUID,
        createdAt: Instant,
    ): ChatMessage = mutex.withLock {
        val key = ConversationKey(userId, chatId)
        val nextSeq = messages[key]?.lastOrNull()?.seq?.plus(1) ?: 1L
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
        messages.getOrPut(key) { ArrayList() } += message
        message
    }

    override suspend fun get(userId: String, chatId: UUID, seq: Long): ChatMessage? = mutex.withLock {
        messages[ConversationKey(userId, chatId)]?.firstOrNull { it.seq == seq }
    }

    override suspend fun getById(
        userId: String,
        chatId: UUID,
        messageId: UUID,
    ): ChatMessage? = mutex.withLock {
        messages[ConversationKey(userId, chatId)]?.firstOrNull { it.id == messageId }
    }

    override suspend fun latest(userId: String, chatId: UUID): ChatMessage? = mutex.withLock {
        messages[ConversationKey(userId, chatId)]?.lastOrNull()
    }

    override suspend fun updateContent(
        userId: String,
        chatId: UUID,
        messageId: UUID,
        content: String,
    ): ChatMessage? = mutex.withLock {
        val key = ConversationKey(userId, chatId)
        val currentMessages = messages[key] ?: return@withLock null
        val index = currentMessages.indexOfFirst { it.id == messageId }
        if (index < 0) return@withLock null

        currentMessages[index].copy(content = content).also { updated ->
            currentMessages[index] = updated
        }
    }

    override suspend fun list(
        userId: String,
        chatId: UUID,
        afterSeq: Long?,
        beforeSeq: Long?,
        limit: Int,
    ): List<ChatMessage> = mutex.withLock {
        val filtered = messages[ConversationKey(userId, chatId)]
            .orEmpty()
            .asSequence()
            .filter { message -> afterSeq == null || message.seq > afterSeq }
            .filter { message -> beforeSeq == null || message.seq < beforeSeq }
            .toList()
        when {
            beforeSeq != null -> filtered.takeLast(limit)
            else -> filtered.take(limit)
        }
    }
}

private data class ConversationKey(
    val userId: String,
    val chatId: UUID,
)
