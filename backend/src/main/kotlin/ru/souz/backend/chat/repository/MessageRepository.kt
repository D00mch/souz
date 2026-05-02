package ru.souz.backend.chat.repository

import java.time.Instant
import java.util.UUID
import ru.souz.backend.chat.model.ChatMessage
import ru.souz.backend.chat.model.ChatRole

interface MessageRepository {
    suspend fun append(
        userId: String,
        chatId: UUID,
        role: ChatRole,
        content: String,
        metadata: Map<String, String> = emptyMap(),
        id: UUID = UUID.randomUUID(),
        createdAt: Instant = Instant.now(),
    ): ChatMessage

    suspend fun get(userId: String, chatId: UUID, seq: Long): ChatMessage?
    suspend fun getById(userId: String, chatId: UUID, messageId: UUID): ChatMessage?

    suspend fun latest(userId: String, chatId: UUID): ChatMessage?

    suspend fun updateContent(
        userId: String,
        chatId: UUID,
        messageId: UUID,
        content: String,
    ): ChatMessage?

    suspend fun list(
        userId: String,
        chatId: UUID,
        afterSeq: Long? = null,
        beforeSeq: Long? = null,
        limit: Int = DEFAULT_LIMIT,
    ): List<ChatMessage>

    companion object {
        const val DEFAULT_LIMIT: Int = 100
        const val MAX_LIMIT: Int = 500
    }
}
