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

    override suspend fun list(
        userId: String,
        chatId: UUID,
        afterSeq: Long?,
        limit: Int,
    ): List<ChatMessage> = mutex.withLock {
        messages[ConversationKey(userId, chatId)]
            .orEmpty()
            .asSequence()
            .filter { message -> afterSeq == null || message.seq > afterSeq }
            .take(limit)
            .toList()
    }
}

private data class ConversationKey(
    val userId: String,
    val chatId: UUID,
)
