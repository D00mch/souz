package ru.souz.backend.events.service

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.events.bus.AgentEventBus
import ru.souz.backend.events.bus.AgentEventLimits
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.storage.memory.MemoryAgentEventRepository
import ru.souz.backend.storage.memory.MemoryChatRepository

class AgentEventServiceTest {
    @Test
    fun `list and stream replay default and clamp limits`() = runTest {
        val chatRepository = MemoryChatRepository()
        val eventRepository = MemoryAgentEventRepository()
        val service = AgentEventService(
            chatRepository = chatRepository,
            eventRepository = eventRepository,
            eventBus = AgentEventBus(),
        )
        val chat = chat(userId = "user-a", title = "Replay caps")
        chatRepository.create(chat)
        repeat(AgentEventLimits.MAX_REPLAY_LIMIT + 5) { index ->
            service.append(
                userId = chat.userId,
                chatId = chat.id,
                executionId = null,
                type = AgentEventType.MESSAGE_CREATED,
                payload = mapOf("index" to index.toString()),
                createdAt = Instant.parse("2026-05-01T10:00:00Z").plusSeconds(index.toLong()),
            )
        }

        val defaultReplay = service.listByChat(userId = chat.userId, chatId = chat.id)
        val clampedReplay = service.listByChat(
            userId = chat.userId,
            chatId = chat.id,
            limit = AgentEventLimits.MAX_REPLAY_LIMIT + 500,
        )
        val afterSeqReplay = service.listByChat(
            userId = chat.userId,
            chatId = chat.id,
            afterSeq = 1_000L,
            limit = AgentEventLimits.MAX_REPLAY_LIMIT + 500,
        )
        val defaultStream = service.openStream(userId = chat.userId, chatId = chat.id)
        val clampedStream = service.openStream(
            userId = chat.userId,
            chatId = chat.id,
            limit = AgentEventLimits.MAX_REPLAY_LIMIT + 500,
        )

        try {
            assertEquals(AgentEventLimits.DEFAULT_REPLAY_LIMIT, defaultReplay.size)
            assertEquals(100L, defaultReplay.last().seq)

            assertEquals(AgentEventLimits.MAX_REPLAY_LIMIT, clampedReplay.size)
            assertEquals(1_000L, clampedReplay.last().seq)

            assertEquals(5, afterSeqReplay.size)
            assertEquals(1_001L, afterSeqReplay.first().seq)
            assertEquals(1_005L, afterSeqReplay.last().seq)

            assertEquals(AgentEventLimits.DEFAULT_REPLAY_LIMIT, defaultStream.replay.size)
            assertEquals(100L, defaultStream.replay.last().seq)

            assertEquals(AgentEventLimits.MAX_REPLAY_LIMIT, clampedStream.replay.size)
            assertEquals(1_000L, clampedStream.replay.last().seq)
        } finally {
            defaultStream.close()
            clampedStream.close()
        }
    }

    @Test
    fun `list rejects non-positive limits`() = runTest {
        val chatRepository = MemoryChatRepository()
        val eventRepository = MemoryAgentEventRepository()
        val service = AgentEventService(
            chatRepository = chatRepository,
            eventRepository = eventRepository,
            eventBus = AgentEventBus(),
        )
        val chat = chat(userId = "user-a", title = "Invalid")
        chatRepository.create(chat)

        assertFailsWith<IllegalArgumentException> {
            service.listByChat(userId = chat.userId, chatId = chat.id, limit = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            service.listByChat(userId = chat.userId, chatId = chat.id, limit = -1)
        }
    }
}

private fun chat(
    userId: String,
    title: String,
): Chat =
    Chat(
        id = UUID.randomUUID(),
        userId = userId,
        title = title,
        archived = false,
        createdAt = Instant.parse("2026-05-01T09:00:00Z"),
        updatedAt = Instant.parse("2026-05-01T09:00:00Z"),
    )
