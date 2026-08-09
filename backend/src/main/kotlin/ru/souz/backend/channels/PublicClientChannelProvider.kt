package ru.souz.backend.channels

import java.util.UUID
import ru.souz.backend.chat.repository.ChatRepository
import ru.souz.backend.chat.repository.MessageRepository
import ru.souz.backend.events.service.AgentEventService

/**
 * Channel provider for chats reached through the public Client–Souz WebSocket contract
 * (`mobile_app` and future WS-onboarded `clientType`s). `"backend"` is the type used for the
 * agent's own first-party sessions and is deliberately excluded — it is not a forwardable channel.
 */
class PublicClientChannelProvider(
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val eventService: AgentEventService,
    /** True if [chatId] is already advertised by a more specific provider (e.g. Telegram). */
    private val isClaimedByAnotherProvider: suspend (chatId: UUID) -> Boolean,
) : ChannelProvider {
    override fun supports(channelType: String): Boolean = channelType != BACKEND_CLIENT_TYPE

    override suspend fun listChannels(userId: String): List<ChannelDescriptor> =
        chatRepository.list(userId, includeArchived = false)
            .filter { it.clientType != BACKEND_CLIENT_TYPE && !isClaimedByAnotherProvider(it.id) }
            .map { chat -> ChannelDescriptor(chat.clientType, chat.id.toString(), chat.title ?: chat.clientType) }

    override suspend fun sendMessage(userId: String, channelId: String, text: String): ChannelSendResult {
        val chatId = runCatching { UUID.fromString(channelId) }.getOrNull()
            ?: return ChannelSendResult.Failed("Invalid channel id.")
        val chat = chatRepository.get(userId, chatId)
            ?.takeIf { it.clientType != BACKEND_CLIENT_TYPE && !isClaimedByAnotherProvider(chatId) }
            ?: return ChannelSendResult.Failed("Channel not found for this user.")
        persistChannelMessage(messageRepository, eventService, userId, chat.id, text)
        return ChannelSendResult.Delivered("Sent to ${chat.title ?: chat.clientType}.")
    }

    private companion object {
        const val BACKEND_CLIENT_TYPE = "backend"
    }
}
