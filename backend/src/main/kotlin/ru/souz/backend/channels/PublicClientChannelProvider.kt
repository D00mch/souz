package ru.souz.backend.channels

import java.util.UUID
import ru.souz.backend.chat.repository.ChatRepository
import ru.souz.backend.client.supportedClientTypes

/**
 * Forwards to chats reached over the public Client–Souz WebSocket contract (`mobile_app` and
 * future WS-onboarded types, per [supportedClientTypes] minus `"backend"`, the agent's own
 * first-party session type). Reports its own fixed [channelType] on every descriptor rather than
 * the chat's raw `clientType` (kept only in the label) — [ChannelProviderRegistry] dispatches
 * `send()` by a single channelType per provider, so the real clientType would fail to route back
 * here.
 */
class PublicClientChannelProvider(
    private val chatRepository: ChatRepository,
    private val deliveryService: ChannelDeliveryService,
    /** True if [chatId] is already advertised by a more specific provider (e.g. Telegram). */
    private val isClaimedByAnotherProvider: suspend (chatId: UUID) -> Boolean,
) : ChannelProvider {
    override val channelType: String = CHANNEL_TYPE

    override suspend fun listChannels(userId: String): List<ChannelDescriptor> =
        chatRepository.list(userId, includeArchived = false)
            .filter { it.clientType in FORWARDABLE_CLIENT_TYPES && !isClaimedByAnotherProvider(it.id) }
            .map { chat -> ChannelDescriptor(channelType, chat.id.toString(), chat.title ?: chat.clientType) }

    override suspend fun sendMessage(userId: String, channelId: String, text: String): ChannelSendResult {
        val chatId = channelId.toChannelUuidOrNull()
            ?: return ChannelSendResult.Failed("Invalid channel id.")
        val chat = deliveryService.resolveTarget(userId, chatId)
            ?.takeIf { it.clientType in FORWARDABLE_CLIENT_TYPES && !isClaimedByAnotherProvider(chatId) }
            ?: return ChannelSendResult.Failed("Channel not found for this user.")
        deliveryService.deliver(userId, chat.id, text)
        return ChannelSendResult.Delivered("Sent to ${chat.title ?: chat.clientType}.")
    }

    private companion object {
        const val CHANNEL_TYPE = "public_client"
        const val BACKEND_CLIENT_TYPE = "backend"
        val FORWARDABLE_CLIENT_TYPES: Set<String> = supportedClientTypes - BACKEND_CLIENT_TYPE
    }
}
