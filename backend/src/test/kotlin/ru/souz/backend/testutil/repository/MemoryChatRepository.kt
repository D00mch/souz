package ru.souz.backend.testutil.repository

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.chat.repository.ChatRequestConflictException
import ru.souz.backend.chat.repository.ChatRepository

class MemoryChatRepository(
    maxEntries: Int,
) : ChatRepository {
    private val mutex = Mutex()
    private val chats = boundedLruMap<ChatKey, Chat>(maxEntries)

    constructor() : this(DEFAULT_MEMORY_REPOSITORY_MAX_ENTRIES)

    override suspend fun create(chat: Chat): Chat = mutex.withLock {
        if (chats.values.any { it.userId == chat.userId && it.requestId == chat.requestId }) {
            throw ChatRequestConflictException(chat.userId, chat.requestId)
        }
        chats[ChatKey(chat.userId, chat.id)] = chat
        chat
    }

    override suspend fun get(userId: String, chatId: UUID): Chat? = mutex.withLock {
        chats[ChatKey(userId, chatId)]
    }

    override suspend fun getById(chatId: UUID): Chat? = mutex.withLock {
        chats.values.firstOrNull { it.id == chatId }
    }

    override suspend fun getByIds(chatIds: List<UUID>): List<Chat> = mutex.withLock {
        val ids = chatIds.toSet()
        chats.values.filter { it.id in ids }
    }

    override suspend fun findByRequestId(userId: String, requestId: String): Chat? = mutex.withLock {
        chats.values.firstOrNull { it.userId == userId && it.requestId == requestId }
    }

    override suspend fun list(
        userId: String,
        limit: Int,
        includeArchived: Boolean,
    ): List<Chat> = mutex.withLock {
        chats.values
            .asSequence()
            .filter { it.userId == userId }
            .filter { includeArchived || !it.archived }
            .sortedByDescending { it.updatedAt }
            .take(limit)
            .toList()
    }

    override suspend fun touchUpdatedAt(userId: String, chatId: UUID, updatedAt: Instant) = mutex.withLock {
        val key = ChatKey(userId, chatId)
        val current = chats[key] ?: return@withLock
        chats[key] = current.copy(updatedAt = maxOf(current.updatedAt, updatedAt))
    }

    override suspend fun updateTitle(
        userId: String,
        chatId: UUID,
        title: String,
        updatedAt: Instant,
    ): Chat? = mutex.withLock {
        val key = ChatKey(userId, chatId)
        val current = chats[key] ?: return@withLock null
        current.copy(
            title = title,
            updatedAt = updatedAt,
        ).also { updated ->
            chats[key] = updated
        }
    }

    override suspend fun updateArchived(
        userId: String,
        chatId: UUID,
        archived: Boolean,
        updatedAt: Instant,
    ): Chat? = mutex.withLock {
        val key = ChatKey(userId, chatId)
        val current = chats[key] ?: return@withLock null
        current.copy(
            archived = archived,
            updatedAt = updatedAt,
        ).also { updated ->
            chats[key] = updated
        }
    }
}

private data class ChatKey(
    val userId: String,
    val chatId: UUID,
)
