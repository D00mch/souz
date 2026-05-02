package ru.souz.backend.events.service

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.events.bus.AgentEventBus
import ru.souz.backend.events.bus.AgentEventLimits
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.storage.memory.MemoryAgentEventRepository
import ru.souz.backend.storage.memory.MemoryChatRepository

class AgentEventServiceTest {
    @Test
    fun `appendDurable persists event and publishes it to live stream`() = runTest {
        val chatRepository = MemoryChatRepository()
        val eventRepository = MemoryAgentEventRepository()
        val service = AgentEventService(
            chatRepository = chatRepository,
            eventRepository = eventRepository,
            eventBus = AgentEventBus(),
        )
        val chat = chat(userId = "user-a", title = "Durable")
        chatRepository.create(chat)
        val stream = service.openStream(userId = chat.userId, chatId = chat.id)

        try {
            val durableEvent = service.appendDurable(
                userId = chat.userId,
                chatId = chat.id,
                executionId = null,
                type = AgentEventType.MESSAGE_CREATED,
                payload = mapOf("messageId" to "message-1"),
                createdAt = Instant.parse("2026-05-01T10:00:00Z"),
            )

            val storedEvents = service.listByChat(userId = chat.userId, chatId = chat.id)
            val liveEvent = withTimeout(1_000) { stream.liveEvents.receive() }

            assertEquals(listOf(durableEvent), storedEvents)
            assertTrue(liveEvent.durable)
            assertEquals(durableEvent.seq, liveEvent.seq)
            assertEquals(AgentEventType.MESSAGE_CREATED, liveEvent.type)
        } finally {
            stream.close()
        }
    }

    @Test
    fun `publishLive publishes event without persisting it or adding replay rows`() = runTest {
        val chatRepository = MemoryChatRepository()
        val eventRepository = MemoryAgentEventRepository()
        val service = AgentEventService(
            chatRepository = chatRepository,
            eventRepository = eventRepository,
            eventBus = AgentEventBus(),
        )
        val chat = chat(userId = "user-a", title = "Live only")
        chatRepository.create(chat)
        val stream = service.openStream(userId = chat.userId, chatId = chat.id)

        try {
            val liveEvent = service.publishLive(
                userId = chat.userId,
                chatId = chat.id,
                executionId = null,
                type = AgentEventType.MESSAGE_DELTA,
                payload = mapOf(
                    "messageId" to "message-1",
                    "delta" to "chunk",
                ),
                createdAt = Instant.parse("2026-05-01T10:00:00Z"),
            )

            val replayEvents = service.listByChat(userId = chat.userId, chatId = chat.id)
            val streamedEvent = withTimeout(1_000) { stream.liveEvents.receive() }

            assertTrue(replayEvents.isEmpty())
            assertFalse(liveEvent.durable)
            assertEquals(null, liveEvent.seq)
            assertEquals(liveEvent, streamedEvent)
        } finally {
            stream.close()
        }
    }

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
            service.appendDurable(
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
