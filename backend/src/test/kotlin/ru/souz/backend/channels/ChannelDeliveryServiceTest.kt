package ru.souz.backend.channels

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.chat.model.ChatRole
import ru.souz.backend.events.bus.AgentEventBus
import ru.souz.backend.events.model.MessageCreatedPayload
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.testutil.repository.MemoryAgentEventRepository
import ru.souz.backend.testutil.repository.MemoryChatRepository
import ru.souz.backend.testutil.repository.MemoryMessageRepository

class ChannelDeliveryServiceTest {
    private val userId = "user-1"

    private fun chat(id: UUID = UUID.randomUUID(), userId: String = this.userId, archived: Boolean = false): Chat {
        val now = Instant.now()
        return Chat(
            id = id,
            userId = userId,
            title = "Chat",
            archived = archived,
            createdAt = now,
            updatedAt = now,
            clientType = "mobile_app",
        )
    }

    private fun service(
        chatRepository: MemoryChatRepository = MemoryChatRepository(),
        messageRepository: MemoryMessageRepository = MemoryMessageRepository(),
        eventRepository: MemoryAgentEventRepository = MemoryAgentEventRepository(),
    ): ChannelDeliveryService = ChannelDeliveryService(
        chatRepository = chatRepository,
        messageRepository = messageRepository,
        eventService = AgentEventService(chatRepository, eventRepository, AgentEventBus()),
    )

    @Test
    fun `resolveTarget returns null for a missing chat`() = runTest {
        val result = service().resolveTarget(userId, UUID.randomUUID())
        assertNull(result)
    }

    @Test
    fun `resolveTarget returns null for an archived chat`() = runTest {
        val chatRepository = MemoryChatRepository()
        val archived = chat(archived = true)
        chatRepository.create(archived)

        assertNull(service(chatRepository).resolveTarget(userId, archived.id))
    }

    @Test
    fun `resolveTarget returns the chat when owned and unarchived`() = runTest {
        val chatRepository = MemoryChatRepository()
        val owned = chat()
        chatRepository.create(owned)

        assertEquals(owned, service(chatRepository).resolveTarget(userId, owned.id))
    }

    @Test
    fun `resolveTargets excludes missing, archived, and unowned chats`() = runTest {
        val chatRepository = MemoryChatRepository()
        val owned = chat()
        val archived = chat(archived = true)
        val otherUsers = chat(userId = "user-2")
        chatRepository.create(owned)
        chatRepository.create(archived)
        chatRepository.create(otherUsers)
        val missingId = UUID.randomUUID()

        val result = service(chatRepository).resolveTargets(
            userId,
            listOf(owned.id, archived.id, otherUsers.id, missingId),
        )

        assertEquals(mapOf(owned.id to owned), result)
    }

    @Test
    fun `deliver appends an assistant message, a durable event, and bumps chat updatedAt`() = runTest {
        val chatRepository = MemoryChatRepository()
        val staleUpdatedAt = Instant.now().minusSeconds(3600)
        val target = chat().copy(updatedAt = staleUpdatedAt)
        chatRepository.create(target)
        val messageRepository = MemoryMessageRepository()
        val eventRepository = MemoryAgentEventRepository()

        service(chatRepository, messageRepository, eventRepository).deliver(userId, target.id, "hello")

        val messages = messageRepository.list(userId, target.id, afterSeq = null, beforeSeq = null, limit = 10)
        assertEquals(1, messages.size)
        assertEquals(ChatRole.ASSISTANT, messages.single().role)
        assertEquals("hello", messages.single().content)

        val events = eventRepository.listByChat(userId, target.id)
        assertEquals(1, events.size)
        assertEquals(null, events.single().executionId)
        assertEquals("hello", (events.single().payload as MessageCreatedPayload).content)

        val updated = chatRepository.get(userId, target.id)
        assertTrue(updated != null && updated.updatedAt.isAfter(staleUpdatedAt))
    }
}
