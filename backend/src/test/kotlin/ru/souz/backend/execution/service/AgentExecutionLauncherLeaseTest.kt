package ru.souz.backend.execution.service

import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ru.souz.backend.agent.runtime.BackendAgentRuntimeEventSink
import ru.souz.backend.agent.runtime.BackendConversationTurnOutcome
import ru.souz.backend.agent.runtime.BackendConversationTurnRunner
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.backend.agent.session.AgentConversationSession
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.client.ClientDevice
import ru.souz.backend.client.ClientThreadRuntimeRegistry
import ru.souz.backend.events.bus.AgentEventBus
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.execution.repository.AgentExecutionRepository
import ru.souz.backend.testutil.repository.MemoryAgentEventRepository
import ru.souz.backend.testutil.repository.MemoryAgentExecutionRepository
import ru.souz.backend.testutil.repository.MemoryAgentStateRepository
import ru.souz.backend.testutil.repository.MemoryChatRepository
import ru.souz.backend.testutil.repository.MemoryMessageRepository
import ru.souz.backend.testutil.repository.MemoryOptionRepository
import ru.souz.backend.testutil.repository.MemoryToolCallRepository
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse

@OptIn(ExperimentalCoroutinesApi::class)
class AgentExecutionLauncherLeaseTest {
    @Test
    fun `tracked client thread is cancelled when lease refresh loses ownership`() = runTest {
        val chatRepository = MemoryChatRepository()
        val messageRepository = MemoryMessageRepository()
        val optionRepository = MemoryOptionRepository()
        val executionRepository = LostLeaseAgentExecutionRepository()
        val eventRepository = MemoryAgentEventRepository()
        val toolCallRepository = MemoryToolCallRepository()
        val registry = ClientThreadRuntimeRegistry(runtimeOwner = "owner-1")
        val eventService = AgentEventService(
            chatRepository = chatRepository,
            eventRepository = eventRepository,
            eventBus = AgentEventBus(),
        )
        val finalizer = AgentExecutionFinalizer(
            agentStateRepository = MemoryAgentStateRepository(),
            chatRepository = chatRepository,
            executionRepository = executionRepository,
            turnRunner = NeverUsedTurnRunner,
            clientThreadRegistry = registry,
        )
        val launcher = AgentExecutionLauncher(
            executionScope = this,
            finalizer = finalizer,
            executionRepository = executionRepository,
            clientThreadRegistry = registry,
            leaseRefreshInterval = Duration.ofMillis(1),
        )
        val chat = Chat(
            id = UUID.randomUUID(),
            userId = "user-lease",
            title = "lease",
            archived = false,
            createdAt = Instant.parse("2026-08-03T00:00:00Z"),
            updatedAt = Instant.parse("2026-08-03T00:00:00Z"),
        )
        chatRepository.create(chat)
        val execution = AgentExecution(
            id = UUID.randomUUID(),
            userId = chat.userId,
            chatId = chat.id,
            userMessageId = null,
            assistantMessageId = null,
            status = AgentExecutionStatus.RUNNING,
            requestId = null,
            clientMessageId = "message-1",
            model = null,
            provider = null,
            startedAt = Instant.parse("2026-08-03T00:00:01Z"),
            finishedAt = null,
            cancelRequested = false,
            errorCode = null,
            errorMessage = null,
            usage = null,
            metadata = emptyMap(),
            runtimeOwner = registry.runtimeOwner,
            runtimeLeaseUntil = Instant.now().plusSeconds(60),
        )
        executionRepository.create(execution)
        registry.register(
            execution.id,
            ClientDevice(
                userId = chat.userId,
                deviceId = "device-1",
                deviceType = "tv_box",
                capabilities = setOf("speech"),
            ),
        )
        val eventSink = BackendAgentRuntimeEventSink(
            userId = chat.userId,
            chatId = chat.id,
            executionId = execution.id,
            messageRepository = messageRepository,
            optionRepository = optionRepository,
            executionRepository = executionRepository,
            eventService = eventService,
            toolCallRepository = toolCallRepository,
            streamingMessagesEnabled = true,
            toolEventsEnabled = true,
            publicClientThread = true,
        )
        val started = CompletableDeferred<Unit>()

        val running = async {
            launcher.runTrackedExecution(execution, eventSink) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        executionRepository.loseLease = true

        advanceTimeBy(1)
        runCurrent()

        assertFailsWith<ExecutionCancelledException> { running.await() }
        assertEquals(
            AgentExecutionStatus.CANCELLED,
            executionRepository.getByChat(chat.userId, chat.id, execution.id)?.status,
        )
    }

    @Test
    fun `tracked client thread keeps terminal durable state when lease refresh sees completion`() = runTest {
        val chatRepository = MemoryChatRepository()
        val messageRepository = MemoryMessageRepository()
        val optionRepository = MemoryOptionRepository()
        val executionRepository = MemoryAgentExecutionRepository()
        val eventRepository = MemoryAgentEventRepository()
        val toolCallRepository = MemoryToolCallRepository()
        val registry = ClientThreadRuntimeRegistry(runtimeOwner = "owner-1")
        val eventService = AgentEventService(
            chatRepository = chatRepository,
            eventRepository = eventRepository,
            eventBus = AgentEventBus(),
        )
        val finalizer = AgentExecutionFinalizer(
            agentStateRepository = MemoryAgentStateRepository(),
            chatRepository = chatRepository,
            executionRepository = executionRepository,
            turnRunner = NeverUsedTurnRunner,
            clientThreadRegistry = registry,
        )
        val launcher = AgentExecutionLauncher(
            executionScope = this,
            finalizer = finalizer,
            executionRepository = executionRepository,
            clientThreadRegistry = registry,
            leaseRefreshInterval = Duration.ofMillis(1),
        )
        val chat = Chat(
            id = UUID.randomUUID(),
            userId = "user-lease-terminal",
            title = "lease terminal",
            archived = false,
            createdAt = Instant.parse("2026-08-03T00:00:00Z"),
            updatedAt = Instant.parse("2026-08-03T00:00:00Z"),
        )
        chatRepository.create(chat)
        val execution = AgentExecution(
            id = UUID.randomUUID(),
            userId = chat.userId,
            chatId = chat.id,
            userMessageId = null,
            assistantMessageId = null,
            status = AgentExecutionStatus.RUNNING,
            requestId = null,
            clientMessageId = "message-1",
            model = null,
            provider = null,
            startedAt = Instant.parse("2026-08-03T00:00:01Z"),
            finishedAt = null,
            cancelRequested = false,
            errorCode = null,
            errorMessage = null,
            usage = null,
            metadata = emptyMap(),
            runtimeOwner = registry.runtimeOwner,
            runtimeLeaseUntil = Instant.now().plusSeconds(60),
        )
        executionRepository.create(execution)
        registry.register(
            execution.id,
            ClientDevice(
                userId = chat.userId,
                deviceId = "device-1",
                deviceType = "tv_box",
                capabilities = setOf("speech"),
            ),
        )
        val eventSink = BackendAgentRuntimeEventSink(
            userId = chat.userId,
            chatId = chat.id,
            executionId = execution.id,
            messageRepository = messageRepository,
            optionRepository = optionRepository,
            executionRepository = executionRepository,
            eventService = eventService,
            toolCallRepository = toolCallRepository,
            streamingMessagesEnabled = true,
            toolEventsEnabled = true,
            publicClientThread = true,
        )
        val started = CompletableDeferred<Unit>()

        val running = async {
            launcher.runTrackedExecution(execution, eventSink) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        executionRepository.update(
            execution.copy(
                status = AgentExecutionStatus.COMPLETED,
                finishedAt = Instant.now(),
            )
        )

        advanceTimeBy(1)
        runCurrent()

        assertTrue(running.isActive)
        assertEquals(
            AgentExecutionStatus.COMPLETED,
            executionRepository.getByChat(chat.userId, chat.id, execution.id)?.status,
        )
        running.cancelAndJoin()
    }

    private object NeverUsedTurnRunner : BackendConversationTurnRunner {
        override suspend fun run(
            conversationKey: AgentConversationKey,
            request: BackendConversationTurnRequest,
            eventSink: ru.souz.agent.runtime.AgentRuntimeEventSink,
            initialUsage: LLMResponse.Usage,
        ): BackendConversationTurnOutcome = BackendConversationTurnOutcome.Completed(
            output = "unused",
            usage = LLMResponse.Usage(0, 0, 0, 0),
            session = AgentConversationSession(
                history = listOf(LLMRequest.Message(LLMMessageRole.user, "unused")),
                temperature = 0.6f,
                locale = "ru-RU",
                timeZone = "Europe/Moscow",
                basedOnMessageSeq = 1,
                rowVersion = 0,
            ),
        )
    }
}

private class LostLeaseAgentExecutionRepository(
    private val delegate: MemoryAgentExecutionRepository = MemoryAgentExecutionRepository(),
) : AgentExecutionRepository by delegate {
    var loseLease: Boolean = false

    override suspend fun refreshClientThreadLease(
        userId: String,
        chatId: UUID,
        executionId: UUID,
        runtimeOwner: String,
        leaseUntil: Instant,
    ): AgentExecution? =
        if (loseLease) null else delegate.refreshClientThreadLease(userId, chatId, executionId, runtimeOwner, leaseUntil)
}
