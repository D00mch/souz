package ru.souz.backend.channels

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import ru.souz.backend.agent.session.InMemoryAgentSessionRepository
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.events.bus.AgentEventBus
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.testutil.repository.MemoryAgentEventRepository
import ru.souz.backend.testutil.repository.MemoryChatRepository
import ru.souz.backend.testutil.repository.MemoryMessageRepository

// Delivery mechanics (message/event persistence, chat updatedAt, session history) live in
// ChannelDeliveryServiceTest; these tests cover only this provider's own routing/validation.
class PublicClientChannelProviderTest {
    private val userId = "user-1"

    private fun chat(clientType: String, archived: Boolean = false, title: String? = "Mobile"): Chat {
        val now = Instant.now()
        return Chat(
            id = UUID.randomUUID(),
            userId = userId,
            title = title,
            archived = archived,
            createdAt = now,
            updatedAt = now,
            clientType = clientType,
        )
    }

    private fun provider(
        chatRepository: MemoryChatRepository = MemoryChatRepository(),
        claimed: Set<UUID> = emptySet(),
    ): PublicClientChannelProvider {
        val deliveryService = ChannelDeliveryService(
            chatRepository = chatRepository,
            messageRepository = MemoryMessageRepository(),
            eventService = AgentEventService(chatRepository, MemoryAgentEventRepository(), AgentEventBus()),
            sessionRepository = InMemoryAgentSessionRepository(),
        )
        return PublicClientChannelProvider(
            chatRepository = chatRepository,
            deliveryService = deliveryService,
            isClaimedByAnotherProvider = { chatId -> chatId in claimed },
        )
    }

    @Test
    fun `listChannels excludes backend and archived chats`() = runTest {
        val chatRepository = MemoryChatRepository()
        val mobile = chat("mobile_app")
        val backend = chat("backend")
        val archived = chat("mobile_app", archived = true)
        chatRepository.create(mobile)
        chatRepository.create(backend)
        chatRepository.create(archived)
        val provider = provider(chatRepository = chatRepository)

        val channels = provider.listChannels(userId)

        // Reports the provider's own channelType, not the chat's raw clientType — SendMessageToChannel
        // must be able to route back to this provider through ChannelProviderRegistry's type-keyed dispatch.
        assertEquals(listOf(ChannelDescriptor(provider.channelType, mobile.id.toString(), "Mobile")), channels)
    }

    @Test
    fun `listChannels excludes chats claimed by another provider`() = runTest {
        val chatRepository = MemoryChatRepository()
        val mobile = chat("mobile_app")
        chatRepository.create(mobile)
        val provider = provider(chatRepository = chatRepository, claimed = setOf(mobile.id))

        assertEquals(emptyList(), provider.listChannels(userId))
    }

    @Test
    fun `sendMessage delivers to an owned unarchived chat`() = runTest {
        val chatRepository = MemoryChatRepository()
        val mobile = chat("mobile_app")
        chatRepository.create(mobile)
        val provider = provider(chatRepository = chatRepository)

        val result = provider.sendMessage(userId, mobile.id.toString(), "hello")

        assertIs<ChannelSendResult.Delivered>(result)
    }

    @Test
    fun `sendMessage fails for an archived chat, matching listChannels`() = runTest {
        val chatRepository = MemoryChatRepository()
        val archived = chat("mobile_app", archived = true)
        chatRepository.create(archived)
        val provider = provider(chatRepository = chatRepository)

        val result = provider.sendMessage(userId, archived.id.toString(), "hello")

        assertIs<ChannelSendResult.Failed>(result)
    }

    @Test
    fun `sendMessage fails for an unknown chat`() = runTest {
        val result = provider().sendMessage(userId, UUID.randomUUID().toString(), "hello")

        assertIs<ChannelSendResult.Failed>(result)
    }

    @Test
    fun `sendMessage fails for a chat claimed by another provider`() = runTest {
        val chatRepository = MemoryChatRepository()
        val mobile = chat("mobile_app")
        chatRepository.create(mobile)
        val provider = provider(chatRepository = chatRepository, claimed = setOf(mobile.id))

        val result = provider.sendMessage(userId, mobile.id.toString(), "hello")

        assertIs<ChannelSendResult.Failed>(result)
    }

    @Test
    fun `listChannels excludes chats with an unrecognized clientType`() = runTest {
        val chatRepository = MemoryChatRepository()
        val unknown = chat("some_made_up_type")
        chatRepository.create(unknown)
        val provider = provider(chatRepository = chatRepository)

        assertEquals(emptyList(), provider.listChannels(userId))
    }

    @Test
    fun `sendMessage rejects a chat with an unrecognized clientType even if the id is otherwise valid`() = runTest {
        val chatRepository = MemoryChatRepository()
        val unknown = chat("some_made_up_type")
        chatRepository.create(unknown)
        val provider = provider(chatRepository = chatRepository)

        val result = provider.sendMessage(userId, unknown.id.toString(), "hello")

        assertIs<ChannelSendResult.Failed>(result)
    }
}
