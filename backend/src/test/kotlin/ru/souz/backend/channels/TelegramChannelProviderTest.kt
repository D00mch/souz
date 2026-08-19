package ru.souz.backend.channels

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import ru.souz.backend.agent.session.InMemoryAgentSessionRepository
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.events.bus.AgentEventBus
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.telegram.TELEGRAM_TEXT_LIMIT
import ru.souz.backend.telegram.TelegramBotApi
import ru.souz.backend.telegram.TelegramBotTokenCrypto
import ru.souz.backend.telegram.TelegramGetMeResponse
import ru.souz.backend.telegram.TelegramUpdatesResponse
import ru.souz.backend.testutil.repository.MemoryAgentEventRepository
import ru.souz.backend.testutil.repository.MemoryChatRepository
import ru.souz.backend.testutil.repository.MemoryMessageRepository
import ru.souz.backend.testutil.repository.MemoryTelegramBotBindingRepository

// Delivery mechanics (message/event persistence, chat updatedAt, session history) live in
// ChannelDeliveryServiceTest; these tests cover only this provider's own binding/chat resolution.
class TelegramChannelProviderTest {
    private val userId = "user-1"
    private val tokenCrypto = TelegramBotTokenCrypto(rawBase64Key = TEST_TELEGRAM_TOKEN_ENCRYPTION_KEY)

    private class FakeTelegramBotApi(
        private val shouldFail: Boolean = false,
        private val failAfterSuccessCount: Int = Int.MAX_VALUE,
    ) : TelegramBotApi {
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
            if (shouldFail || sent.size >= failAfterSuccessCount) error("boom")
            sent += SentMessage(token, chatId, text)
        }

        override suspend fun sendChatAction(token: String, chatId: Long, action: String) = Unit
        override suspend fun deleteWebhook(token: String, dropPendingUpdates: Boolean) = Unit
    }

    /** A binding, claimed and linked, with an owned+unarchived chat row unless [createChat] is false. */
    private suspend fun bindAndLink(
        bindingRepository: MemoryTelegramBotBindingRepository,
        chatRepository: MemoryChatRepository,
        chatId: UUID = UUID.randomUUID(),
        telegramChatId: Long = 999L,
        createChat: Boolean = true,
        archived: Boolean = false,
    ) {
        val stored = bindingRepository.upsertForChat(userId, chatId, "raw-token", "hash-$chatId", "secret-$chatId", now = Instant.now())
        bindingRepository.claimTelegramUser(stored.id, "secret-$chatId", 1L, telegramChatId, null, null, null, Instant.now())
        if (createChat) {
            val now = Instant.now()
            chatRepository.create(
                Chat(chatId, userId, "Telegram", archived, now, now, clientType = "backend"),
            )
        }
    }

    private fun provider(
        bindingRepository: MemoryTelegramBotBindingRepository,
        chatRepository: MemoryChatRepository = MemoryChatRepository(),
        messageRepository: MemoryMessageRepository = MemoryMessageRepository(),
        api: FakeTelegramBotApi = FakeTelegramBotApi(),
    ): Pair<TelegramChannelProvider, MemoryMessageRepository> {
        val deliveryService = ChannelDeliveryService(
            chatRepository = chatRepository,
            messageRepository = messageRepository,
            eventService = AgentEventService(chatRepository, MemoryAgentEventRepository(), AgentEventBus()),
            sessionRepository = InMemoryAgentSessionRepository(),
        )
        val provider = TelegramChannelProvider(
            bindingRepository = bindingRepository,
            deliveryService = deliveryService,
            telegramBotApi = api,
            tokenCrypto = tokenCrypto,
        )
        return provider to messageRepository
    }

    @Test
    fun `listChannels only returns enabled and linked bindings backed by an owned chat`() = runTest {
        val bindingRepository = MemoryTelegramBotBindingRepository()
        val chatRepository = MemoryChatRepository()
        val linkedChatId = UUID.randomUUID()
        val disabledChatId = UUID.randomUUID()
        val unlinkedChatId = UUID.randomUUID()
        bindAndLink(bindingRepository, chatRepository, linkedChatId)
        bindAndLink(bindingRepository, chatRepository, disabledChatId)
        bindingRepository.getByUserAndChat(userId, disabledChatId)!!.let {
            bindingRepository.markError(it.id, "err", Instant.now(), disable = true)
        }
        bindingRepository.upsertForChat(userId, unlinkedChatId, "tok3", "hash3", "secret3", now = Instant.now())
        val (provider, _) = provider(bindingRepository, chatRepository)

        val channels = provider.listChannels(userId)

        assertEquals(listOf(linkedChatId.toString()), channels.map { it.channelId })
    }

    @Test
    fun `listChannels excludes an orphaned binding with no chat row`() = runTest {
        val bindingRepository = MemoryTelegramBotBindingRepository()
        val chatRepository = MemoryChatRepository()
        bindAndLink(bindingRepository, chatRepository, createChat = false)
        val (provider, _) = provider(bindingRepository, chatRepository)

        assertEquals(emptyList(), provider.listChannels(userId))
    }

    @Test
    fun `listChannels excludes an archived chat, matching PublicClientChannelProvider`() = runTest {
        val bindingRepository = MemoryTelegramBotBindingRepository()
        val chatRepository = MemoryChatRepository()
        bindAndLink(bindingRepository, chatRepository, archived = true)
        val (provider, _) = provider(bindingRepository, chatRepository)

        assertEquals(emptyList(), provider.listChannels(userId))
    }

    @Test
    fun `sendMessage fails for an orphaned binding with no chat row`() = runTest {
        val bindingRepository = MemoryTelegramBotBindingRepository()
        val chatRepository = MemoryChatRepository()
        val chatId = UUID.randomUUID()
        bindAndLink(bindingRepository, chatRepository, chatId, createChat = false)
        val (provider, _) = provider(bindingRepository, chatRepository)

        val result = provider.sendMessage(userId, chatId.toString(), "hello")

        assertIs<ChannelSendResult.Failed>(result)
    }

    @Test
    fun `sendMessage fails for an archived chat, matching listChannels`() = runTest {
        val bindingRepository = MemoryTelegramBotBindingRepository()
        val chatRepository = MemoryChatRepository()
        val chatId = UUID.randomUUID()
        bindAndLink(bindingRepository, chatRepository, chatId, archived = true)
        val (provider, _) = provider(bindingRepository, chatRepository)

        val result = provider.sendMessage(userId, chatId.toString(), "hello")

        assertIs<ChannelSendResult.Failed>(result)
    }

    @Test
    fun `sendMessage success sends via Telegram and delivers the message`() = runTest {
        val bindingRepository = MemoryTelegramBotBindingRepository()
        val chatRepository = MemoryChatRepository()
        val chatId = UUID.randomUUID()
        bindAndLink(bindingRepository, chatRepository, chatId, telegramChatId = 999L)
        val api = FakeTelegramBotApi()
        val (provider, messageRepository) = provider(bindingRepository, chatRepository, api = api)

        val result = provider.sendMessage(userId, chatId.toString(), "hello")

        assertIs<ChannelSendResult.Delivered>(result)
        assertEquals(1, api.sent.size)
        assertEquals(999L, api.sent.single().chatId)
        assertEquals(1, messageRepository.list(userId, chatId, afterSeq = null, beforeSeq = null, limit = 10).size)
    }

    @Test
    fun `sendMessage failure does not persist a message`() = runTest {
        val bindingRepository = MemoryTelegramBotBindingRepository()
        val chatRepository = MemoryChatRepository()
        val chatId = UUID.randomUUID()
        bindAndLink(bindingRepository, chatRepository, chatId)
        val (provider, messageRepository) = provider(bindingRepository, chatRepository, api = FakeTelegramBotApi(shouldFail = true))

        val result = provider.sendMessage(userId, chatId.toString(), "hello")

        assertIs<ChannelSendResult.Failed>(result)
        assertEquals(emptyList(), messageRepository.list(userId, chatId, afterSeq = null, beforeSeq = null, limit = 10))
    }

    @Test
    fun `sendMessage chunks text longer than the Telegram limit but persists it as one message`() = runTest {
        val bindingRepository = MemoryTelegramBotBindingRepository()
        val chatRepository = MemoryChatRepository()
        val chatId = UUID.randomUUID()
        bindAndLink(bindingRepository, chatRepository, chatId)
        val api = FakeTelegramBotApi()
        val (provider, messageRepository) = provider(bindingRepository, chatRepository, api = api)
        val longText = "a".repeat(TELEGRAM_TEXT_LIMIT * 2 + 10)

        val result = provider.sendMessage(userId, chatId.toString(), longText)

        assertIs<ChannelSendResult.Delivered>(result)
        assertTrue(api.sent.size > 1)
        assertTrue(api.sent.all { it.text.length <= TELEGRAM_TEXT_LIMIT })
        assertEquals(longText, api.sent.joinToString("") { it.text })
        val messages = messageRepository.list(userId, chatId, afterSeq = null, beforeSeq = null, limit = 10)
        assertEquals(1, messages.size)
        assertEquals(longText, messages.single().content)
    }

    @Test
    fun `sendMessage persists only the chunks actually delivered before a later chunk fails`() = runTest {
        val bindingRepository = MemoryTelegramBotBindingRepository()
        val chatRepository = MemoryChatRepository()
        val chatId = UUID.randomUUID()
        bindAndLink(bindingRepository, chatRepository, chatId)
        val api = FakeTelegramBotApi(failAfterSuccessCount = 1)
        val (provider, messageRepository) = provider(bindingRepository, chatRepository, api = api)
        val longText = "a".repeat(TELEGRAM_TEXT_LIMIT * 2 + 10)

        val result = provider.sendMessage(userId, chatId.toString(), longText)

        assertIs<ChannelSendResult.Failed>(result)
        assertEquals(1, api.sent.size)
        val messages = messageRepository.list(userId, chatId, afterSeq = null, beforeSeq = null, limit = 10)
        assertEquals(1, messages.size)
        assertEquals(api.sent.single().text, messages.single().content)
        assertTrue(messages.single().content.length < longText.length)
    }

    @Test
    fun `sendMessage fails for unknown chat`() = runTest {
        val (provider, _) = provider(MemoryTelegramBotBindingRepository())

        val result = provider.sendMessage(userId, UUID.randomUUID().toString(), "hello")

        assertIs<ChannelSendResult.Failed>(result)
    }
}

private const val TEST_TELEGRAM_TOKEN_ENCRYPTION_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
