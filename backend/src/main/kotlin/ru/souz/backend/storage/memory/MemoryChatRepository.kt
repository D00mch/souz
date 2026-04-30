package ru.souz.backend.storage.memory

import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.chat.repository.ChatRepository

class MemoryChatRepository : ChatRepository {
    private val mutex = Mutex()
    private val chats = LinkedHashMap<ChatKey, Chat>()

    override suspend fun create(chat: Chat): Chat = mutex.withLock {
        chats[ChatKey(chat.userId, chat.id)] = chat
        chat
    }

    override suspend fun get(userId: String, chatId: UUID): Chat? = mutex.withLock {
        chats[ChatKey(userId, chatId)]
    }

    override suspend fun list(userId: String, limit: Int): List<Chat> = mutex.withLock {
        chats.values
            .asSequence()
            .filter { it.userId == userId }
            .sortedByDescending { it.updatedAt }
            .take(limit)
            .toList()
    }
}

private data class ChatKey(
    val userId: String,
    val chatId: UUID,
)
