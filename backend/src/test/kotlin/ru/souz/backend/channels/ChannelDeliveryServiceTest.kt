package ru.souz.backend.channels

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.chat.model.ChatRole
import ru.souz.backend.chat.model.CROSS_CHANNEL_MESSAGE_METADATA_KEY
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
    fun `resolve targets require an owned unarchived chat`() = runTest {
        val chatRepository = MemoryChatRepository()
        val owned = chat()
        val archived = chat(archived = true)
        val otherUsers = chat(userId = "user-2")
        chatRepository.create(owned)
        chatRepository.create(archived)
        chatRepository.create(otherUsers)
        val missingId = UUID.randomUUID()
        val deliveryService = service(chatRepository)

        assertEquals(owned, deliveryService.resolveTarget(userId, owned.id))
        assertNull(deliveryService.resolveTarget(userId, archived.id))
        assertNull(deliveryService.resolveTarget(userId, otherUsers.id))
        assertNull(deliveryService.resolveTarget(userId, missingId))
        assertEquals(
            mapOf(owned.id to owned),
            deliveryService.resolveTargets(userId, listOf(owned.id, archived.id, otherUsers.id, missingId)),
        )
    }

    @Test
    fun `deliver persists the message without overwriting concurrent chat changes`() = runTest {
        val chatRepository = MemoryChatRepository()
        val staleUpdatedAt = Instant.now().minusSeconds(3600)
        val concurrentUpdatedAt = Instant.now().minusSeconds(60)
        val target = chat().copy(updatedAt = staleUpdatedAt)
        chatRepository.create(target)
        chatRepository.updateTitle(userId, target.id, "Renamed", concurrentUpdatedAt)
        chatRepository.updateArchived(userId, target.id, archived = true, updatedAt = concurrentUpdatedAt)
        val messageRepository = MemoryMessageRepository()
        val eventRepository = MemoryAgentEventRepository()

        service(chatRepository, messageRepository, eventRepository).deliver(userId, target.id, "hello")

        val messages = messageRepository.list(userId, target.id, afterSeq = null, beforeSeq = null, limit = 10)
        assertEquals(1, messages.size)
        assertEquals(ChatRole.ASSISTANT, messages.single().role)
        assertEquals("hello", messages.single().content)
        assertEquals("true", messages.single().metadata[CROSS_CHANNEL_MESSAGE_METADATA_KEY])

        val events = eventRepository.listByChat(userId, target.id)
        assertEquals(1, events.size)
        assertEquals(null, events.single().executionId)
        assertEquals("hello", (events.single().payload as MessageCreatedPayload).content)

        val updated = assertNotNull(chatRepository.get(userId, target.id))
        assertTrue(updated.updatedAt.isAfter(staleUpdatedAt))
        assertEquals("Renamed", updated.title)
        assertEquals(true, updated.archived)
    }
}
