package ru.souz.backend.chat.repository

import java.util.UUID
import ru.souz.backend.chat.model.Chat

interface ChatRepository {
    suspend fun create(chat: Chat): Chat
    suspend fun get(userId: String, chatId: UUID): Chat?
    suspend fun list(
        userId: String,
        limit: Int = DEFAULT_LIMIT,
        includeArchived: Boolean = false,
    ): List<Chat>
    suspend fun update(chat: Chat): Chat

    companion object {
        const val DEFAULT_LIMIT: Int = 50
        const val MAX_LIMIT: Int = 100
    }
}
