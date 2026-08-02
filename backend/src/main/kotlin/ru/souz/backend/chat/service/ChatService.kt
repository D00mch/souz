package ru.souz.backend.chat.service

import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.backend.client.PublicPayloadHash
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.chat.repository.ChatRepository
import ru.souz.backend.chat.repository.MessageRepository
import ru.souz.backend.common.normalizePositiveLimit
import ru.souz.backend.http.BackendV1Exception
import ru.souz.backend.http.invalidV1Request

data class ChatSummary(
    val chat: Chat,
    val lastMessagePreview: String?,
)

data class ChatListPage(
    val items: List<ChatSummary>,
    val nextCursor: String?,
)

data class CreateClientChatResult(
    val chat: Chat,
    val duplicate: Boolean,
)

class ChatService(
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
) {
    private val createMutex = Mutex()

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

    suspend fun createClient(
        userId: String,
        requestId: String,
        clientType: String,
        title: String?,
    ): CreateClientChatResult = createMutex.withLock {
        val normalizedTitle = title?.trim()?.takeIf { it.isNotEmpty() }
        val payloadHash = PublicPayloadHash.ofValue(
            linkedMapOf(
                "clientType" to clientType,
                "title" to normalizedTitle,
            )
        )
        chatRepository.findByRequestId(userId, requestId)?.let { existing ->
            if (existing.payloadHash != payloadHash) {
                throw BackendV1Exception(
                    status = HttpStatusCode.Conflict,
                    code = "idempotency_conflict",
                    message = "requestId was already used with a different chat payload.",
                )
            }
            return@withLock CreateClientChatResult(existing, duplicate = true)
        }
        val now = Instant.now()
        CreateClientChatResult(
            chat = chatRepository.create(
                Chat(
                    id = UUID.randomUUID(),
                    userId = userId,
                    title = normalizedTitle,
                    archived = false,
                    createdAt = now,
                    updatedAt = now,
                    clientType = clientType,
                    requestId = requestId,
                    payloadHash = payloadHash,
                )
            ),
            duplicate = false,
        )
    }

    suspend fun updateTitle(
        userId: String,
        chatId: UUID,
        title: String,
    ): Chat {
        requireOwnedChat(userId, chatId)
        val normalizedTitle = title.trim().takeIf { it.isNotEmpty() }
            ?: throw invalidV1Request("title must not be empty.")
        return chatRepository.updateTitle(
            userId = userId,
            chatId = chatId,
            title = normalizedTitle,
            updatedAt = Instant.now(),
        ) ?: throw chatNotFound()
    }

    suspend fun setArchived(
        userId: String,
        chatId: UUID,
        archived: Boolean,
    ): Chat {
        requireOwnedChat(userId, chatId)
        return chatRepository.updateArchived(
            userId = userId,
            chatId = chatId,
            archived = archived,
            updatedAt = Instant.now(),
        ) ?: throw chatNotFound()
    }

    private suspend fun requireOwnedChat(userId: String, chatId: UUID): Chat =
        chatRepository.get(userId, chatId) ?: throw chatNotFound()

    private fun chatNotFound(): BackendV1Exception =
        BackendV1Exception(
            status = HttpStatusCode.NotFound,
            code = "chat_not_found",
            message = "Chat not found.",
        )
}
