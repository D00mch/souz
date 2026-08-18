package ru.souz.backend.channels

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    override val channelType: String = "telegram"

    override suspend fun listChannels(userId: String): List<ChannelDescriptor> = coroutineScope {
        val bindings = bindingRepository.listForUser(userId).filter { it.active }
        // Resolved concurrently rather than one getById per binding awaited in turn — a chat lookup
        // per Telegram binding, but not one that has to wait on the previous one to finish first.
        val chats = bindings.map { binding -> async { chatRepository.getById(binding.chatId) } }.awaitAll()
        bindings.zip(chats).mapNotNull { (binding, chat) ->
            // An archived chat stops being forwardable, matching PublicClientChannelProvider's
            // includeArchived = false — otherwise a Telegram-bound chat the user archived would
            // keep appearing here forever while its public-client equivalent correctly disappears.
            if (chat?.archived == true) return@mapNotNull null
            ChannelDescriptor(
                channelType = channelType,
                channelId = binding.chatId.toString(),
                label = chat?.title ?: binding.telegramUsername ?: binding.telegramFirstName ?: "Telegram",
            )
        }
    }

    override suspend fun sendMessage(userId: String, channelId: String, text: String): ChannelSendResult {
        val chatId = channelId.toChannelUuidOrNull()
            ?: return ChannelSendResult.Failed("Invalid channel id.")
        val binding = bindingRepository.getByUserAndChat(userId, chatId)?.takeIf { it.active }
            ?: return ChannelSendResult.Failed("Telegram channel not found or not linked.")
        if (chatRepository.get(userId, chatId)?.archived == true) {
            return ChannelSendResult.Failed("Telegram channel not found or not linked.")
        }
        val telegramChatId = binding.telegramChatId
            ?: return ChannelSendResult.Failed("Telegram channel not found or not linked.")
        val token = try {
            tokenCrypto.decrypt(binding.botTokenEncrypted)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ChannelSendResult.Failed("Telegram delivery failed: ${e.message}")
        }
        val chunks = telegramTextChunks(text, TELEGRAM_TEXT_LIMIT)
        var sentCount = 0
        for (chunk in chunks) {
            try {
                telegramBotApi.sendMessage(token, telegramChatId, chunk)
                sentCount += 1
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Earlier chunks in this loop already reached Telegram and can't be un-sent — persist
                // exactly what was actually delivered so Souz's own history matches reality, and report
                // Failed (not Delivered) so the caller knows not to blindly resend the whole message.
                if (sentCount > 0) {
                    val delivered = chunks.take(sentCount).joinToString("")
                    persistChannelMessage(chatRepository, messageRepository, eventService, userId, binding.chatId, delivered)
                }
                return ChannelSendResult.Failed(
                    "Telegram delivery failed after $sentCount/${chunks.size} part(s): ${e.message}"
                )
            }
        }
        persistChannelMessage(chatRepository, messageRepository, eventService, userId, binding.chatId, text)
        return ChannelSendResult.Delivered("Sent via Telegram.")
    }
}
