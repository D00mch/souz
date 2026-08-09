package ru.souz.backend.channels

import java.util.UUID
import ru.souz.backend.chat.model.ChatRole
import ru.souz.backend.chat.repository.MessageRepository
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.events.model.MessageCreatedPayload
import ru.souz.backend.events.service.AgentEventService

/** A user-facing communication channel a message can be forwarded to (Telegram, a public-client chat, ...). */
data class ChannelDescriptor(
    val channelType: String,
    val channelId: String,
    val label: String,
)

sealed interface ChannelSendResult {
    data class Delivered(val detail: String) : ChannelSendResult
    data class Failed(val reason: String) : ChannelSendResult
}

/** One implementation per channel type — see `backend/src/main/kotlin/ru/souz/backend/channels/` siblings. */
interface ChannelProvider {
    fun supports(channelType: String): Boolean

    suspend fun listChannels(userId: String): List<ChannelDescriptor>

    suspend fun sendMessage(userId: String, channelId: String, text: String): ChannelSendResult
}

/** Aggregates all registered [ChannelProvider]s; adding a new channel type means binding one more provider here. */
class ChannelProviderRegistry(private val providers: List<ChannelProvider>) {
    suspend fun listAll(userId: String): List<ChannelDescriptor> =
        providers.flatMap { it.listChannels(userId) }

    suspend fun send(userId: String, channelType: String, channelId: String, text: String): ChannelSendResult =
        providers.firstOrNull { it.supports(channelType) }?.sendMessage(userId, channelId, text)
            ?: ChannelSendResult.Failed("Unknown or unsupported channel type: '$channelType'.")
}

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
