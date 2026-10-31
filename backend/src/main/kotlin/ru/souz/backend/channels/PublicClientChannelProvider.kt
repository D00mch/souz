package ru.souz.backend.channels

import java.util.UUID
import ru.souz.backend.attachments.AttachmentException
import ru.souz.backend.attachments.AttachmentStorage
import ru.souz.backend.attachments.attachmentId
import ru.souz.backend.attachments.toContent
import ru.souz.llms.MAX_MESSAGE_FILES
import ru.souz.llms.MAX_MESSAGE_FILE_BYTES
import ru.souz.backend.chat.repository.ChatRepository
import ru.souz.backend.client.supportedClientTypes

/**
 * Forwards to chats reached over the public Client–Souz WebSocket contract, covering every
 * `clientType` in [supportedClientTypes] (`backend`, `mobile_app`, and future WS-onboarded types
 * alike) not already claimed by a more specific provider — registered last in
 * [ChannelProviderRegistry] as the catch-all fallback. Reports its own fixed [channelType] on
 * every descriptor rather than the chat's raw `clientType` (kept only in the label) —
 * [ChannelProviderRegistry] dispatches `send()` by a single channelType per provider, so the real
 * clientType would fail to route back here.
 */
class PublicClientChannelProvider(
    private val chatRepository: ChatRepository,
    private val deliveryService: ChannelDeliveryService,
    /** True if [chatId] is already advertised by a more specific provider (e.g. Telegram). */
    private val isClaimedByAnotherProvider: suspend (chatId: UUID) -> Boolean,
    private val attachmentStorage: AttachmentStorage? = null,
) : ChannelProvider {
    override val channelType: String = CHANNEL_TYPE

    override suspend fun listChannels(userId: String): List<ChannelDescriptor> =
        chatRepository.clientToolChannels(userId).map { it.chat }
            .filterNot { isClaimedByAnotherProvider(it.id) }
            .map { chat -> ChannelDescriptor(channelType, chat.id.toString(), chat.title ?: chat.clientType) }

    override suspend fun sendMessage(userId: String, channelId: String, text: String): ChannelSendResult =
        sendAttachments(userId, channelId, text, emptyList())

    override suspend fun sendAttachments(userId: String, channelId: String, text: String, fileIds: List<String>): ChannelSendResult {
        val chatId = channelId.toChannelUuidOrNull()
            ?: return ChannelSendResult.Failed("Invalid channel id.")
        val chat = deliveryService.resolveTarget(userId, chatId)
            ?.takeIf { it.clientType in supportedClientTypes && !isClaimedByAnotherProvider(chatId) }
            ?: return ChannelSendResult.Failed("Channel not found for this user.")
        val files = try {
            require(fileIds.size <= MAX_MESSAGE_FILES && fileIds.distinct().size == fileIds.size)
            fileIds.map { requireNotNull(attachmentStorage).get(userId, attachmentId(it)).toContent() }
                .also { require(it.sumOf { file -> file.sizeBytes } <= MAX_MESSAGE_FILE_BYTES) }
        } catch (error: AttachmentException) {
            return ChannelSendResult.Failed("Attachments are unavailable for this user.")
        } catch (error: IllegalArgumentException) {
            return ChannelSendResult.Failed("Invalid attachments or attachment storage is not configured.")
        }
        deliveryService.deliver(userId, chat.id, text, files)
        return ChannelSendResult.Delivered("Sent to ${chat.title ?: chat.clientType}.")
    }

    private companion object {
        const val CHANNEL_TYPE = "public_client"
    }
}
