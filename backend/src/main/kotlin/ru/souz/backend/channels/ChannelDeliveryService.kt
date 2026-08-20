package ru.souz.backend.channels

import java.util.UUID
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.chat.model.ChatRole
import ru.souz.backend.chat.model.CROSS_CHANNEL_MESSAGE_METADATA_KEY
import ru.souz.backend.chat.repository.ChatRepository
import ru.souz.backend.chat.repository.MessageRepository
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.events.model.MessageCreatedPayload
import ru.souz.backend.events.service.AgentEventService

/**
 * Shared by every [ChannelProvider]: resolves/validates forward targets and persists a delivered
 * cross-channel push into the target chat, so providers themselves only discover targets and do
 * the external delivery.
 */
class ChannelDeliveryService(
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val eventService: AgentEventService,
) {
    /** An owned, unarchived chat — the only kind of target a message can be forwarded into. */
    suspend fun resolveTarget(userId: String, chatId: UUID): Chat? =
        chatRepository.get(userId, chatId)?.takeUnless { it.archived }

    /** Batch form of [resolveTarget]; a missing or unowned/archived id is simply absent from the result. */
    suspend fun resolveTargets(userId: String, chatIds: List<UUID>): Map<UUID, Chat> =
        chatRepository.getByIds(chatIds)
            .filter { it.userId == userId && !it.archived }
            .associateBy { it.id }

    suspend fun deliver(userId: String, chatId: UUID, text: String) {
        val message = messageRepository.append(
            userId,
            chatId,
            ChatRole.ASSISTANT,
            text,
            metadata = mapOf(CROSS_CHANNEL_MESSAGE_METADATA_KEY to "true"),
        )
        chatRepository.get(userId, chatId)?.let { chat -> chatRepository.update(chat.copy(updatedAt = message.createdAt)) }
        eventService.append(
            userId = userId,
            chatId = chatId,
            executionId = null,
            type = AgentEventType.MESSAGE_CREATED,
            payload = MessageCreatedPayload(message.id, message.seq, message.role.value, message.content),
        )
    }
}
