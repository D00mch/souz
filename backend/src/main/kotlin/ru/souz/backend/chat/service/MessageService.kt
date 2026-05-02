package ru.souz.backend.chat.service

import io.ktor.http.HttpStatusCode
import java.util.UUID
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.chat.repository.ChatRepository
import ru.souz.backend.chat.repository.MessageRepository
import ru.souz.backend.common.normalizePositiveLimit
import ru.souz.backend.execution.service.AgentExecutionService
import ru.souz.backend.http.BackendV1Exception
import ru.souz.backend.settings.service.UserSettingsOverrides

class MessageService(
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val executionService: AgentExecutionService,
) {
    suspend fun list(
        userId: String,
        chatId: UUID,
        beforeSeq: Long? = null,
        afterSeq: Long? = null,
        limit: Int = MessageRepository.DEFAULT_LIMIT,
    ): MessageListPage {
        requireOwnedChat(userId, chatId)
        val normalizedLimit = normalizePositiveLimit(limit, MessageRepository.MAX_LIMIT)
        return MessageListPage(
            items = messageRepository.list(
                userId = userId,
                chatId = chatId,
                afterSeq = afterSeq,
                beforeSeq = beforeSeq,
                limit = normalizedLimit,
            ),
            nextBeforeSeq = null,
        )
    }

    suspend fun send(
        userId: String,
        chatId: UUID,
        content: String,
        clientMessageId: String? = null,
        requestOverrides: UserSettingsOverrides = UserSettingsOverrides(),
    ): SendMessageResult =
        executionService.executeChatTurn(
            userId = userId,
            chatId = chatId,
            content = content,
            clientMessageId = clientMessageId,
            requestOverrides = requestOverrides,
        )

    private suspend fun requireOwnedChat(userId: String, chatId: UUID): Chat =
        chatRepository.get(userId, chatId)
            ?: throw BackendV1Exception(
                status = HttpStatusCode.NotFound,
                code = "chat_not_found",
                message = "Chat not found.",
            )
}
