package ru.souz.backend.channels

import kotlinx.coroutines.CancellationException
import ru.souz.backend.vk.VkBotApi
import ru.souz.backend.storage.postgres.PostgresVkBotBindingRepository
import ru.souz.backend.vk.VkBotTokenCrypto

class VkChannelProvider(
    private val bindingRepository: PostgresVkBotBindingRepository,
    private val deliveryService: ChannelDeliveryService,
    private val vkBotApi: VkBotApi,
    private val tokenCrypto: VkBotTokenCrypto,
) : ChannelProvider {
    override val channelType: String = "vk"

    override suspend fun listChannels(userId: String): List<ChannelDescriptor> {
        val bindings = bindingRepository.listForUser(userId).filter { it.active }
        val chatsById = deliveryService.resolveTargets(userId, bindings.map { it.chatId })
        return bindings.mapNotNull { binding ->
            val chat = chatsById[binding.chatId] ?: return@mapNotNull null
            ChannelDescriptor(
                channelType = channelType,
                channelId = binding.chatId.toString(),
                label = chat.title ?: binding.vkFirstName ?: binding.vkGroupName ?: "VK",
            )
        }
    }

    override suspend fun sendMessage(userId: String, channelId: String, text: String): ChannelSendResult {
        val chatId = channelId.toChannelUuidOrNull()
            ?: return ChannelSendResult.Failed("Invalid channel id.")
        val binding = bindingRepository.getByUserAndChat(userId, chatId)?.takeIf { it.active }
            ?: return ChannelSendResult.Failed("VK channel not found or not linked.")
        deliveryService.resolveTarget(userId, chatId)
            ?: return ChannelSendResult.Failed("VK channel not found or not linked.")
        val peerId = binding.vkPeerId
            ?: return ChannelSendResult.Failed("VK channel not found or not linked.")
        val token = try {
            tokenCrypto.decrypt(binding.groupTokenEncrypted)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ChannelSendResult.Failed("VK delivery failed.")
        }
        return deliveryService.sendChunks(userId, chatId, text, "VK") { chunk ->
            vkBotApi.sendMessage(token, peerId, chunk)
        }
    }
}
