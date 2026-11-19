package ru.souz.backend.channels

import ru.souz.backend.attachments.AttachmentException
import ru.souz.backend.attachments.AttachmentStorage
import ru.souz.backend.attachments.attachmentId
import ru.souz.backend.attachments.toContent
import ru.souz.llms.MAX_MESSAGE_FILES
import ru.souz.llms.MAX_MESSAGE_FILE_BYTES
import ru.souz.backend.chat.repository.ChatRepository
import ru.souz.backend.client.supportedClientTypes

/** Delivers to owned public WebSocket chats by their stable chat IDs. */
class PublicClientChannelProvider(
    private val chatRepository: ChatRepository,
    private val deliveryService: ChannelDeliveryService,
    private val attachmentStorage: AttachmentStorage? = null,
) : ChannelProvider {
    override val channelType: String = CHANNEL_TYPE

    override suspend fun listChannels(userId: String): List<ChannelDescriptor> =
        chatRepository.listClientChannels(userId)
            .map { chat -> ChannelDescriptor(channelType, chat.id.toString(), chat.title ?: chat.clientType) }

    override suspend fun sendMessage(userId: String, channelId: String, text: String): ChannelSendResult =
        sendAttachments(userId, channelId, text, emptyList())

    override suspend fun sendAttachments(userId: String, channelId: String, text: String, fileIds: List<String>): ChannelSendResult {
        val chatId = channelId.toChannelUuidOrNull()
            ?: return ChannelSendResult.Failed("Invalid channel id.")
        val chat = deliveryService.resolveTarget(userId, chatId)
            ?.takeIf { it.clientType in supportedClientTypes }
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
