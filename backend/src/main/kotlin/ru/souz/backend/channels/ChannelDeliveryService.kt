package ru.souz.backend.channels

import java.util.UUID
import ru.souz.backend.client.toClientAttachment
import ru.souz.backend.storage.postgres.PostgresAttachmentRepository
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.chat.model.ChatRole
import ru.souz.backend.chat.model.CROSS_CHANNEL_MESSAGE_METADATA_KEY
import ru.souz.backend.chat.repository.ChatRepository
import ru.souz.backend.chat.repository.MessageRepository
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.events.model.MessageCreatedPayload
import ru.souz.backend.events.service.AgentEventService
import ru.souz.llms.FileContent

/** Persists cross-channel messages and publishes them to the destination chat. */
class ChannelDeliveryService(
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val eventService: AgentEventService,
    private val attachmentRepository: PostgresAttachmentRepository? = null,
) {
    /** An owned, unarchived chat — the only kind of target a message can be forwarded into. */
    suspend fun resolveTarget(userId: String, chatId: UUID): Chat? =
        chatRepository.get(userId, chatId)?.takeUnless { it.archived }

    suspend fun deliver(userId: String, chatId: UUID, text: String, attachments: List<FileContent> = emptyList()) {
        val references = attachments.map { it.toClientAttachment(userId, attachmentRepository) }
        val message = messageRepository.append(
            userId,
            chatId,
            ChatRole.ASSISTANT,
            text,
            metadata = mapOf(CROSS_CHANNEL_MESSAGE_METADATA_KEY to "true"),
            attachments = attachments,
        )
        chatRepository.touchUpdatedAt(userId, chatId, message.createdAt)
        eventService.append(
            userId = userId,
            chatId = chatId,
            executionId = null,
            type = AgentEventType.MESSAGE_CREATED,
            payload = MessageCreatedPayload(message.id, message.seq, message.role.value, message.content, attachments = references),
        )
    }
}
