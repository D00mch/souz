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
import ru.souz.backend.events.model.MessageCreatedPayload
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.testutil.repository.MemoryAgentEventRepository
import ru.souz.backend.testutil.repository.MemoryChatRepository
import ru.souz.backend.testutil.repository.MemoryMessageRepository

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
        messageRepository: MemoryMessageRepository = MemoryMessageRepository(),
        eventRepository: MemoryAgentEventRepository = MemoryAgentEventRepository(),
        claimed: Set<UUID> = emptySet(),
    ): Triple<PublicClientChannelProvider, MemoryMessageRepository, MemoryAgentEventRepository> {
        val eventService = AgentEventService(chatRepository, eventRepository, AgentEventBus())
        val provider = PublicClientChannelProvider(
            chatRepository = chatRepository,
            messageRepository = messageRepository,
            eventService = eventService,
            isClaimedByAnotherProvider = { chatId -> chatId in claimed },
        )
        return Triple(provider, messageRepository, eventRepository)
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
        val (provider, _, _) = provider(chatRepository = chatRepository)

        val channels = provider.listChannels(userId)

        assertEquals(listOf(ChannelDescriptor("mobile_app", mobile.id.toString(), "Mobile")), channels)
    }

    @Test
    fun `listChannels excludes chats claimed by another provider`() = runTest {
        val chatRepository = MemoryChatRepository()
        val mobile = chat("mobile_app")
        chatRepository.create(mobile)
        val (provider, _, _) = provider(chatRepository = chatRepository, claimed = setOf(mobile.id))

        assertEquals(emptyList(), provider.listChannels(userId))
    }

    @Test
    fun `sendMessage persists an assistant message and a durable message-created event`() = runTest {
        val chatRepository = MemoryChatRepository()
        val mobile = chat("mobile_app")
        chatRepository.create(mobile)
        val (provider, messageRepository, eventRepository) = provider(chatRepository = chatRepository)

        val result = provider.sendMessage(userId, mobile.id.toString(), "hello")

        assertIs<ChannelSendResult.Delivered>(result)
        val messages = messageRepository.list(userId, mobile.id, afterSeq = null, beforeSeq = null, limit = 10)
        assertEquals(1, messages.size)
        assertEquals(ChatRole.ASSISTANT, messages.single().role)
        assertEquals("hello", messages.single().content)

        val events = eventRepository.listByChat(userId, mobile.id)
        assertEquals(1, events.size)
        val event = events.single()
        assertEquals(null, event.executionId)
        val payload = event.payload
        assertIs<MessageCreatedPayload>(payload)
        assertEquals("hello", payload.content)
    }

    @Test
    fun `sendMessage fails for an unknown chat`() = runTest {
        val (provider, _, _) = provider()

        val result = provider.sendMessage(userId, UUID.randomUUID().toString(), "hello")

        assertIs<ChannelSendResult.Failed>(result)
    }

    @Test
    fun `sendMessage fails for a chat claimed by another provider`() = runTest {
        val chatRepository = MemoryChatRepository()
        val mobile = chat("mobile_app")
        chatRepository.create(mobile)
        val (provider, _, _) = provider(chatRepository = chatRepository, claimed = setOf(mobile.id))

        val result = provider.sendMessage(userId, mobile.id.toString(), "hello")

        assertIs<ChannelSendResult.Failed>(result)
    }
}
