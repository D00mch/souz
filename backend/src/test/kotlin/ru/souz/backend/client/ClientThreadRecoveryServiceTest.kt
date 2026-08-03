package ru.souz.backend.client

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.events.bus.AgentEventBus
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.testutil.repository.MemoryAgentEventRepository
import ru.souz.backend.testutil.repository.MemoryAgentExecutionRepository
import ru.souz.backend.testutil.repository.MemoryChatRepository

class ClientThreadRecoveryServiceTest {
    @Test
    fun `startup recovery leaves live leased client threads running`() = runBlocking {
        val fixture = recoveryFixture()
        val userId = UUID.randomUUID().toString()
        val liveChat = chat(userId = userId)
        val expiredChat = chat(userId = userId)
        val liveThreadId = UUID.randomUUID()
        val expiredThreadId = UUID.randomUUID()
        fixture.chatRepository.create(liveChat)
        fixture.chatRepository.create(expiredChat)
        fixture.executionRepository.create(
            clientExecution(
                userId = userId,
                chatId = liveChat.id,
                threadId = liveThreadId,
                leaseUntil = Instant.now().plusSeconds(3_600),
            )
        )
        fixture.executionRepository.create(
            clientExecution(
                userId = userId,
                chatId = expiredChat.id,
                threadId = expiredThreadId,
                leaseUntil = Instant.now().minusSeconds(60),
            )
        )

        fixture.recovery.recover()

        val liveExecution = fixture.executionRepository.getByChat(userId, liveChat.id, liveThreadId)
        val expiredExecution = fixture.executionRepository.getByChat(userId, expiredChat.id, expiredThreadId)
        val liveEvents = fixture.eventRepository.listByChat(userId, liveChat.id)
        val expiredEvents = fixture.eventRepository.listByChat(userId, expiredChat.id)
        assertEquals(AgentExecutionStatus.RUNNING, liveExecution?.status)
        assertEquals(AgentExecutionStatus.FAILED, expiredExecution?.status)
        assertTrue(liveEvents.none { it.type == AgentEventType.THREAD_FAILED })
        assertEquals(listOf(AgentEventType.THREAD_FAILED), expiredEvents.map { it.type })
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `recovery sweep retries after a retained lease expires`() = runTest {
        val clock = MutableClock(Instant.parse("2026-08-02T21:00:00Z"))
        val fixture = recoveryFixture(
            clock = clock,
            recoveryInterval = Duration.ofSeconds(1),
        )
        val userId = UUID.randomUUID().toString()
        val chat = chat(userId = userId)
        val threadId = UUID.randomUUID()
        fixture.chatRepository.create(chat)
        fixture.executionRepository.create(
            clientExecution(
                userId = userId,
                chatId = chat.id,
                threadId = threadId,
                leaseUntil = clock.instant().plusSeconds(5),
            )
        )

        fixture.recovery.recover()
        assertEquals(AgentExecutionStatus.RUNNING, fixture.executionRepository.getByChat(userId, chat.id, threadId)?.status)

        clock.current = clock.current.plusSeconds(6)
        val job = fixture.recovery.start(this)
        advanceTimeBy(1_000)
        runCurrent()
        job.cancelAndJoin()

        val recoveredExecution = fixture.executionRepository.getByChat(userId, chat.id, threadId)
        val events = fixture.eventRepository.listByChat(userId, chat.id)
        assertEquals(AgentExecutionStatus.FAILED, recoveredExecution?.status)
        assertEquals(listOf(AgentEventType.THREAD_FAILED), events.map { it.type })
    }

    private fun recoveryFixture(
        clock: Clock = Clock.systemUTC(),
        recoveryInterval: Duration = ClientThreadRuntimeRegistry.LEASE_REFRESH_INTERVAL,
    ): RecoveryFixture {
        val chatRepository = MemoryChatRepository()
        val executionRepository = MemoryAgentExecutionRepository()
        val eventRepository = MemoryAgentEventRepository()
        val eventService = AgentEventService(
            chatRepository = chatRepository,
            eventRepository = eventRepository,
            eventBus = AgentEventBus(),
        )
        return RecoveryFixture(
            chatRepository = chatRepository,
            executionRepository = executionRepository,
            eventRepository = eventRepository,
            recovery = ClientThreadRecoveryService(
                executionRepository = executionRepository,
                eventService = eventService,
                clock = clock,
                recoveryInterval = recoveryInterval,
            ),
        )
    }
}

private data class RecoveryFixture(
    val chatRepository: MemoryChatRepository,
    val executionRepository: MemoryAgentExecutionRepository,
    val eventRepository: MemoryAgentEventRepository,
    val recovery: ClientThreadRecoveryService,
)

private fun chat(userId: String): Chat {
    val now = Instant.parse("2026-08-03T00:00:00Z")
    return Chat(
        id = UUID.randomUUID(),
        userId = userId,
        title = null,
        archived = false,
        createdAt = now,
        updatedAt = now,
        clientType = "backend",
        requestId = UUID.randomUUID().toString(),
    )
}

private fun clientExecution(
    userId: String,
    chatId: UUID,
    threadId: UUID,
    leaseUntil: Instant,
): AgentExecution =
    AgentExecution(
        id = threadId,
        userId = userId,
        chatId = chatId,
        userMessageId = null,
        assistantMessageId = null,
        status = AgentExecutionStatus.RUNNING,
        requestId = null,
        clientMessageId = null,
        model = null,
        provider = null,
        startedAt = Instant.now(),
        finishedAt = null,
        cancelRequested = false,
        errorCode = null,
        errorMessage = null,
        usage = null,
        metadata = emptyMap(),
        runtimeOwner = "test-owner",
        runtimeLeaseUntil = leaseUntil,
    )

private class MutableClock(
    var current: Instant,
) : Clock() {
    override fun getZone(): ZoneId = ZoneId.of("UTC")

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = current
}
