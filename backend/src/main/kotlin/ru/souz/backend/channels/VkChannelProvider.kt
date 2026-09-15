package ru.souz.backend.channels

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import ru.souz.backend.vk.VkBotApi
import ru.souz.backend.vk.VkBotBindingRepository
import ru.souz.backend.vk.VkBotTokenCrypto
import ru.souz.backend.vk.vkTextChunks

class VkChannelProvider(
    private val bindingRepository: VkBotBindingRepository,
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
            return ChannelSendResult.Failed("VK delivery failed: ${e.message}")
        }
        val chunks = vkTextChunks(text)
        val sentChunks = mutableListOf<String>()
        val failure = try {
            for (chunk in chunks) {
                vkBotApi.sendMessage(token, peerId, chunk)
                sentChunks += chunk
            }
            null
        } catch (e: Exception) {
            e
        }
        if (sentChunks.isNotEmpty()) {
            withContext(NonCancellable) {
                deliveryService.deliver(userId, binding.chatId, sentChunks.joinToString(""))
            }
        }
        return when (failure) {
            null -> ChannelSendResult.Delivered("Sent via VK.")
            is CancellationException -> throw failure
            else -> ChannelSendResult.Failed(
                "VK delivery failed after ${sentChunks.size}/${chunks.size} part(s): ${failure.message}"
            )
        }
    }
}
