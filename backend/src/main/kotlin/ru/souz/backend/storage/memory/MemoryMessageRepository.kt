package ru.souz.backend.storage.memory

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.backend.chat.model.ChatMessage
import ru.souz.backend.chat.model.ChatRole
import ru.souz.backend.chat.repository.MessageRepository

class MemoryMessageRepository(
    maxEntries: Int,
) : MessageRepository {
    private val mutex = Mutex()
    private val messages = boundedLruMap<MessageKey, ChatMessage>(maxEntries)
    private val nextSeqByConversation = boundedLruMap<ConversationKey, Long>(maxEntries)

    constructor() : this(DEFAULT_MEMORY_REPOSITORY_MAX_ENTRIES)

    override suspend fun append(
        userId: String,
        chatId: UUID,
        role: ChatRole,
        content: String,
        metadata: Map<String, String>,
        id: UUID,
        createdAt: Instant,
    ): ChatMessage = mutex.withLock {
        val conversationKey = ConversationKey(userId, chatId)
        val nextSeq = (nextSeqByConversation[conversationKey] ?: 0L) + 1L
        nextSeqByConversation[conversationKey] = nextSeq
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
        messages[MessageKey(userId, chatId, id)] = message
        message
    }

    override suspend fun get(userId: String, chatId: UUID, seq: Long): ChatMessage? = mutex.withLock {
        messagesForConversation(userId, chatId).firstOrNull { it.seq == seq }
    }

    override suspend fun getById(
        userId: String,
        chatId: UUID,
        messageId: UUID,
    ): ChatMessage? = mutex.withLock {
        messages[MessageKey(userId, chatId, messageId)]
    }

    override suspend fun latest(userId: String, chatId: UUID): ChatMessage? = mutex.withLock {
        messagesForConversation(userId, chatId).lastOrNull()
    }

    override suspend fun updateContent(
        userId: String,
        chatId: UUID,
        messageId: UUID,
        content: String,
    ): ChatMessage? = mutex.withLock {
        val key = MessageKey(userId, chatId, messageId)
        messages[key]?.copy(content = content)?.also { updated ->
            messages[key] = updated
        }
    }

    override suspend fun list(
        userId: String,
        chatId: UUID,
        afterSeq: Long?,
        beforeSeq: Long?,
        limit: Int,
    ): List<ChatMessage> = mutex.withLock {
        val filtered = messagesForConversation(userId, chatId)
            .asSequence()
            .filter { message -> afterSeq == null || message.seq > afterSeq }
            .filter { message -> beforeSeq == null || message.seq < beforeSeq }
            .toList()
        when {
            beforeSeq != null -> filtered.takeLast(limit)
            else -> filtered.take(limit)
        }
    }

    private fun messagesForConversation(userId: String, chatId: UUID): List<ChatMessage> =
        messages.values
            .asSequence()
            .filter { it.userId == userId && it.chatId == chatId }
            .sortedBy { it.seq }
            .toList()
}

private data class ConversationKey(
    val userId: String,
    val chatId: UUID,
)

private data class MessageKey(
    val userId: String,
    val chatId: UUID,
    val messageId: UUID,
)
