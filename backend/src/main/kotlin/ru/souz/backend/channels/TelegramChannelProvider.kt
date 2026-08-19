package ru.souz.backend.channels

import kotlinx.coroutines.CancellationException
import ru.souz.backend.telegram.TelegramBotApi
import ru.souz.backend.telegram.TelegramBotBindingRepository
import ru.souz.backend.telegram.TelegramBotTokenCrypto
import ru.souz.backend.telegram.telegramTextChunks

class TelegramChannelProvider(
    private val bindingRepository: TelegramBotBindingRepository,
    private val deliveryService: ChannelDeliveryService,
    private val telegramBotApi: TelegramBotApi,
    private val tokenCrypto: TelegramBotTokenCrypto,
) : ChannelProvider {
    override val channelType: String = "telegram"

    override suspend fun listChannels(userId: String): List<ChannelDescriptor> {
        val bindings = bindingRepository.listForUser(userId).filter { it.active }
        val chatsById = deliveryService.resolveTargets(userId, bindings.map { it.chatId })
        return bindings.mapNotNull { binding ->
            val chat = chatsById[binding.chatId] ?: return@mapNotNull null
            ChannelDescriptor(
                channelType = channelType,
                channelId = binding.chatId.toString(),
                label = chat.title ?: binding.telegramUsername ?: binding.telegramFirstName ?: "Telegram",
            )
        }
    }

    override suspend fun sendMessage(userId: String, channelId: String, text: String): ChannelSendResult {
        val chatId = channelId.toChannelUuidOrNull()
            ?: return ChannelSendResult.Failed("Invalid channel id.")
        val binding = bindingRepository.getByUserAndChat(userId, chatId)?.takeIf { it.active }
            ?: return ChannelSendResult.Failed("Telegram channel not found or not linked.")
        deliveryService.resolveTarget(userId, chatId)
            ?: return ChannelSendResult.Failed("Telegram channel not found or not linked.")
        val telegramChatId = binding.telegramChatId
            ?: return ChannelSendResult.Failed("Telegram channel not found or not linked.")
        val token = try {
            tokenCrypto.decrypt(binding.botTokenEncrypted)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ChannelSendResult.Failed("Telegram delivery failed: ${e.message}")
        }
        val chunks = telegramTextChunks(text)
        var sentCount = 0
        for (chunk in chunks) {
            try {
                telegramBotApi.sendMessage(token, telegramChatId, chunk)
                sentCount += 1
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Chunks already sent can't be un-sent — persist what actually reached Telegram.
                if (sentCount > 0) {
                    deliveryService.deliver(userId, binding.chatId, chunks.take(sentCount).joinToString(""))
                }
                return ChannelSendResult.Failed(
                    "Telegram delivery failed after $sentCount/${chunks.size} part(s): ${e.message}"
                )
            }
        }
        deliveryService.deliver(userId, binding.chatId, text)
        return ChannelSendResult.Delivered("Sent via Telegram.")
    }
}
