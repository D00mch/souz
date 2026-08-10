package ru.souz.backend.channels

import java.util.UUID
import kotlinx.coroutines.CancellationException
import ru.souz.backend.chat.repository.ChatRepository
import ru.souz.backend.chat.repository.MessageRepository
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.telegram.TELEGRAM_TEXT_LIMIT
import ru.souz.backend.telegram.TelegramBotApi
import ru.souz.backend.telegram.TelegramBotBindingRepository
import ru.souz.backend.telegram.TelegramBotTokenCrypto
import ru.souz.backend.telegram.telegramTextChunks

class TelegramChannelProvider(
    private val bindingRepository: TelegramBotBindingRepository,
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val eventService: AgentEventService,
    private val telegramBotApi: TelegramBotApi,
    private val tokenCrypto: TelegramBotTokenCrypto,
) : ChannelProvider {
    override fun supports(channelType: String): Boolean = channelType == CHANNEL_TYPE

    override suspend fun listChannels(userId: String): List<ChannelDescriptor> =
        bindingRepository.listForUser(userId)
            .filter { it.enabled && it.linked }
            .map { binding ->
                val title = chatRepository.getById(binding.chatId)?.title
                ChannelDescriptor(
                    channelType = CHANNEL_TYPE,
                    channelId = binding.chatId.toString(),
                    label = title ?: binding.telegramUsername ?: binding.telegramFirstName ?: "Telegram",
                )
            }

    override suspend fun sendMessage(userId: String, channelId: String, text: String): ChannelSendResult {
        val chatId = runCatching { UUID.fromString(channelId) }.getOrNull()
            ?: return ChannelSendResult.Failed("Invalid channel id.")
        val binding = bindingRepository.getByUserAndChat(userId, chatId)?.takeIf { it.enabled && it.linked }
            ?: return ChannelSendResult.Failed("Telegram channel not found or not linked.")
        val telegramChatId = binding.telegramChatId
            ?: return ChannelSendResult.Failed("Telegram channel not found or not linked.")
        return try {
            val token = tokenCrypto.decrypt(binding.botTokenEncrypted)
            telegramTextChunks(text, TELEGRAM_TEXT_LIMIT).forEach { chunk ->
                telegramBotApi.sendMessage(token, telegramChatId, chunk)
            }
            persistChannelMessage(messageRepository, eventService, userId, binding.chatId, text)
            ChannelSendResult.Delivered("Sent via Telegram.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ChannelSendResult.Failed("Telegram delivery failed: ${e.message}")
        }
    }

    private companion object {
        const val CHANNEL_TYPE = "telegram"
    }
}
