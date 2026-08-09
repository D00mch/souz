package ru.souz.backend.channels

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.chat.model.ChatRole
import ru.souz.backend.events.bus.AgentEventBus
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.telegram.TelegramBotApi
import ru.souz.backend.telegram.TelegramBotTokenCrypto
import ru.souz.backend.telegram.TelegramGetMeResponse
import ru.souz.backend.telegram.TelegramUpdatesResponse
import ru.souz.backend.testutil.repository.MemoryAgentEventRepository
import ru.souz.backend.testutil.repository.MemoryChatRepository
import ru.souz.backend.testutil.repository.MemoryMessageRepository
import ru.souz.backend.testutil.repository.MemoryTelegramBotBindingRepository

class TelegramChannelProviderTest {
    private val userId = "user-1"
    private val tokenCrypto = TelegramBotTokenCrypto(rawBase64Key = TEST_TELEGRAM_TOKEN_ENCRYPTION_KEY)

    private class FakeTelegramBotApi(private val shouldFail: Boolean = false) : TelegramBotApi {
        data class SentMessage(val token: String, val chatId: Long, val text: String)

        val sent = mutableListOf<SentMessage>()

        override suspend fun getMe(token: String): TelegramGetMeResponse = error("Not used in this test")
        override suspend fun getUpdates(
            token: String,
            offset: Long?,
            timeoutSeconds: Int,
            allowedUpdates: List<String>,
        ): TelegramUpdatesResponse = error("Not used in this test")

        override suspend fun sendMessage(token: String, chatId: Long, text: String) {
            if (shouldFail) error("boom")
            sent += SentMessage(token, chatId, text)
        }

        override suspend fun sendChatAction(token: String, chatId: Long, action: String) = Unit
        override suspend fun deleteWebhook(token: String, dropPendingUpdates: Boolean) = Unit
    }

    private fun provider(
        bindingRepository: MemoryTelegramBotBindingRepository,
        chatRepository: MemoryChatRepository = MemoryChatRepository(),
        messageRepository: MemoryMessageRepository = MemoryMessageRepository(),
        eventRepository: MemoryAgentEventRepository = MemoryAgentEventRepository(),
        api: FakeTelegramBotApi = FakeTelegramBotApi(),
    ): Triple<TelegramChannelProvider, MemoryMessageRepository, MemoryAgentEventRepository> {
        val eventService = AgentEventService(chatRepository, eventRepository, AgentEventBus())
        val provider = TelegramChannelProvider(
            bindingRepository = bindingRepository,
            chatRepository = chatRepository,
            messageRepository = messageRepository,
            eventService = eventService,
            telegramBotApi = api,
            tokenCrypto = tokenCrypto,
        )
        return Triple(provider, messageRepository, eventRepository)
    }

    @Test
    fun `listChannels only returns enabled and linked bindings`() = runTest {
        val bindingRepository = MemoryTelegramBotBindingRepository()
        val linkedChatId = UUID.randomUUID()
        val disabledChatId = UUID.randomUUID()
        val unlinkedChatId = UUID.randomUUID()
        bindingRepository.upsertForChat(
            userId, linkedChatId, "tok1", "hash1", "secret1", now = Instant.now(),
        ).let { bindingRepository.claimTelegramUser(it.id, "secret1", 1L, 42L, null, null, null, Instant.now()) }
        bindingRepository.upsertForChat(
            userId, disabledChatId, "tok2", "hash2", "secret2", now = Instant.now(),
        ).let {
            bindingRepository.claimTelegramUser(it.id, "secret2", 2L, 43L, null, null, null, Instant.now())
            bindingRepository.markError(it.id, "err", Instant.now(), disable = true)
        }
        bindingRepository.upsertForChat(userId, unlinkedChatId, "tok3", "hash3", "secret3", now = Instant.now())
        val (provider, _, _) = provider(bindingRepository)

        val channels = provider.listChannels(userId)

        assertEquals(listOf(linkedChatId.toString()), channels.map { it.channelId })
    }

    @Test
    fun `sendMessage success sends via Telegram and persists a durable event`() = runTest {
        val bindingRepository = MemoryTelegramBotBindingRepository()
        val chatId = UUID.randomUUID()
        val stored = bindingRepository.upsertForChat(userId, chatId, "raw-token", "hash", "secret", now = Instant.now())
        bindingRepository.claimTelegramUser(stored.id, "secret", 1L, 999L, null, null, null, Instant.now())
        val api = FakeTelegramBotApi()
        val (provider, messageRepository, eventRepository) = provider(bindingRepository, api = api)

        val result = provider.sendMessage(userId, chatId.toString(), "hello")

        assertIs<ChannelSendResult.Delivered>(result)
        assertEquals(1, api.sent.size)
        assertEquals(999L, api.sent.single().chatId)
        val messages = messageRepository.list(userId, chatId, afterSeq = null, beforeSeq = null, limit = 10)
        assertEquals(ChatRole.ASSISTANT, messages.single().role)
        assertEquals(1, eventRepository.listByChat(userId, chatId).size)
    }

    @Test
    fun `sendMessage failure does not persist a message`() = runTest {
        val bindingRepository = MemoryTelegramBotBindingRepository()
        val chatId = UUID.randomUUID()
        val stored = bindingRepository.upsertForChat(userId, chatId, "raw-token", "hash", "secret", now = Instant.now())
        bindingRepository.claimTelegramUser(stored.id, "secret", 1L, 999L, null, null, null, Instant.now())
        val (provider, messageRepository, _) = provider(bindingRepository, api = FakeTelegramBotApi(shouldFail = true))

        val result = provider.sendMessage(userId, chatId.toString(), "hello")

        assertIs<ChannelSendResult.Failed>(result)
        assertEquals(emptyList(), messageRepository.list(userId, chatId, afterSeq = null, beforeSeq = null, limit = 10))
    }

    @Test
    fun `sendMessage fails for unknown chat`() = runTest {
        val (provider, _, _) = provider(MemoryTelegramBotBindingRepository())

        val result = provider.sendMessage(userId, UUID.randomUUID().toString(), "hello")

        assertIs<ChannelSendResult.Failed>(result)
    }
}

private const val TEST_TELEGRAM_TOKEN_ENCRYPTION_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
