package ru.souz.backend.chat.service

import java.time.Instant
import java.util.UUID
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.chat.repository.ChatRepository
import ru.souz.backend.chat.repository.MessageRepository
import ru.souz.backend.common.normalizePositiveLimit

data class ChatSummary(
    val chat: Chat,
    val lastMessagePreview: String?,
)

data class ChatListPage(
    val items: List<ChatSummary>,
    val nextCursor: String?,
)

class ChatService(
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
) {
    suspend fun list(
        userId: String,
        limit: Int = ChatRepository.DEFAULT_LIMIT,
        includeArchived: Boolean = false,
    ): ChatListPage {
        val normalizedLimit = normalizePositiveLimit(limit, ChatRepository.MAX_LIMIT)
        val chats = chatRepository.list(
            userId = userId,
            limit = normalizedLimit,
            includeArchived = includeArchived,
        )
        return ChatListPage(
            items = chats.map { chat ->
                ChatSummary(
                    chat = chat,
                    lastMessagePreview = messageRepository.latest(userId, chat.id)?.content,
                )
            },
            nextCursor = null,
        )
    }

    suspend fun create(
        userId: String,
        title: String?,
    ): Chat {
        val now = Instant.now()
        return chatRepository.create(
            Chat(
                id = UUID.randomUUID(),
                userId = userId,
                title = title?.trim()?.takeIf { it.isNotEmpty() },
                archived = false,
                createdAt = now,
                updatedAt = now,
            )
        )
    }
}
