package ru.souz.backend.channels

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.session.AgentConversationSession
import ru.souz.backend.agent.session.AgentSessionRepository
import ru.souz.backend.agent.session.AgentStateConflictException
import ru.souz.backend.agent.session.InMemoryAgentSessionRepository
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.chat.model.ChatRole
import ru.souz.backend.events.bus.AgentEventBus
import ru.souz.backend.events.model.MessageCreatedPayload
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.testutil.repository.MemoryAgentEventRepository
import ru.souz.backend.testutil.repository.MemoryChatRepository
import ru.souz.backend.testutil.repository.MemoryMessageRepository
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest

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
        sessionRepository: AgentSessionRepository = InMemoryAgentSessionRepository(),
    ): ChannelDeliveryService = ChannelDeliveryService(
        chatRepository = chatRepository,
        messageRepository = messageRepository,
        eventService = AgentEventService(chatRepository, eventRepository, AgentEventBus()),
        sessionRepository = sessionRepository,
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

    @Test
    fun `deliver appends the forwarded text to an existing session's history`() = runTest {
        val chatRepository = MemoryChatRepository()
        val target = chat()
        chatRepository.create(target)
        val sessionRepository = InMemoryAgentSessionRepository()
        val key = AgentConversationKey(userId, target.id.toString())
        sessionRepository.save(
            key,
            AgentConversationSession(history = emptyList(), temperature = 0.7f, locale = "ru-RU", timeZone = "UTC"),
        )

        service(chatRepository, sessionRepository = sessionRepository).deliver(userId, target.id, "hello")

        val history = sessionRepository.load(key)?.history.orEmpty()
        assertEquals(listOf(LLMRequest.Message(role = LLMMessageRole.assistant, content = "hello")), history)
    }

    @Test
    fun `deliver does nothing to session history when no session exists yet`() = runTest {
        val chatRepository = MemoryChatRepository()
        val target = chat()
        chatRepository.create(target)
        val sessionRepository = InMemoryAgentSessionRepository()

        // Must not throw even though there's no prior session for this chat.
        service(chatRepository, sessionRepository = sessionRepository).deliver(userId, target.id, "hello")

        assertNull(sessionRepository.load(AgentConversationKey(userId, target.id.toString())))
    }

    @Test
    fun `deliver swallows a session save conflict instead of failing the whole delivery`() = runTest {
        val chatRepository = MemoryChatRepository()
        val target = chat()
        chatRepository.create(target)
        val messageRepository = MemoryMessageRepository()
        val conflictingSessionRepository = object : AgentSessionRepository {
            override suspend fun load(key: AgentConversationKey): AgentConversationSession =
                AgentConversationSession(history = emptyList(), temperature = 0.7f, locale = "ru-RU", timeZone = "UTC")

            override suspend fun save(key: AgentConversationKey, session: AgentConversationSession) {
                throw AgentStateConflictException(key.userId, UUID.fromString(key.conversationId), 0L)
            }
        }

        // Must not throw: the message/event persistence above already succeeded and should stand.
        service(chatRepository, messageRepository, sessionRepository = conflictingSessionRepository)
            .deliver(userId, target.id, "hello")

        assertEquals(1, messageRepository.list(userId, target.id, afterSeq = null, beforeSeq = null, limit = 10).size)
    }
}
