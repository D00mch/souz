package ru.souz.backend.channels

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
        val sentChunks = mutableListOf<String>()
        val failure = try {
            for (chunk in chunks) {
                telegramBotApi.sendMessage(token, telegramChatId, chunk)
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
            null -> ChannelSendResult.Delivered("Sent via Telegram.")
            is CancellationException -> throw failure
            else -> ChannelSendResult.Failed(
                "Telegram delivery failed after ${sentChunks.size}/${chunks.size} part(s): ${failure.message}"
            )
        }
    }
}
