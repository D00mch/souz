package ru.souz.backend.channels

import java.util.UUID
import ru.souz.backend.chat.model.ChatRole
import ru.souz.backend.chat.repository.MessageRepository
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.events.model.MessageCreatedPayload
import ru.souz.backend.events.service.AgentEventService

/**
 * Persists a successfully delivered cross-channel push into the target chat's own history: an
 * ASSISTANT message plus a durable `message.created` event with `executionId = null`, the signal
 * `isPublicClientEvent()` keys on to admit it into the public WS live stream (see EventRoutes.kt).
 * Shared by every [ChannelProvider] so the out-of-band-push contract stays in exactly one place.
 */
internal suspend fun persistChannelMessage(
    messageRepository: MessageRepository,
    eventService: AgentEventService,
    userId: String,
    chatId: UUID,
    text: String,
) {
    val message = messageRepository.append(userId, chatId, ChatRole.ASSISTANT, text)
    eventService.append(
        userId = userId,
        chatId = chatId,
        executionId = null,
        type = AgentEventType.MESSAGE_CREATED,
        payload = MessageCreatedPayload(message.id, message.seq, message.role.value, message.content),
    )
}
