package ru.souz.backend.chat.repository

import java.util.UUID
import ru.souz.backend.chat.model.Chat

interface ChatRepository {
    suspend fun create(chat: Chat): Chat
    suspend fun get(userId: String, chatId: UUID): Chat?
    suspend fun list(userId: String, limit: Int = DEFAULT_LIMIT): List<Chat>

    companion object {
        const val DEFAULT_LIMIT: Int = 50
    }
}
