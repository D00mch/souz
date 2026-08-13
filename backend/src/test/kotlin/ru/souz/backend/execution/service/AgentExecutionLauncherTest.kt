package ru.souz.backend.execution.service

import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.backend.agent.runtime.BackendAgentRuntimeEventSink
import ru.souz.backend.agent.runtime.BackendConversationTurnOutcome
import ru.souz.backend.agent.runtime.BackendConversationTurnRunner
import ru.souz.backend.agent.session.AgentConversationSession
import ru.souz.backend.chat.model.Chat
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

class AgentExecutionLauncherTest {
    @Test
    fun `registered background execution can be cancelled through registry`() = runBlocking {
        launcherFixture().use { fixture ->
            val bodyStarted = CompletableDeferred<Unit>()

            val job = fixture.launcher.launchRegistered(
                execution = fixture.execution,
                eventSink = fixture.eventSink,
            ) {
                bodyStarted.complete(Unit)
                awaitCancellation()
            }

            assertTrue(fixture.registry.contains(fixture.execution.id))
            withTimeout(5_000) { bodyStarted.await() }
            assertTrue(fixture.launcher.cancel(fixture.execution.id))
            withTimeout(5_000) { job.join() }

            assertEquals(AgentExecutionStatus.CANCELLED, fixture.storedExecution().status)
            assertFalse(fixture.registry.contains(fixture.execution.id))
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `cancellation before body dispatch finalizes without entering execution body`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        launcherFixture(scope = scope).use { fixture ->
            var bodyStarted = false
            val job = fixture.launcher.launchRegistered(
                execution = fixture.execution,
                eventSink = fixture.eventSink,
            ) {
                bodyStarted = true
            }

            assertTrue(fixture.launcher.cancel(fixture.execution.id))
            runCurrent()
            withTimeout(5_000) { job.join() }

            assertFalse(bodyStarted)
            assertEquals(AgentExecutionStatus.CANCELLED, fixture.storedExecution().status)
            assertFalse(fixture.registry.contains(fixture.execution.id))
        }
    }

    @Test
    fun `cancelled execution stays registered until persistence and events finish`() = runBlocking {
        val finalizationStarted = CompletableDeferred<Unit>()
        val allowFinalization = CompletableDeferred<Unit>()
        val repository = BlockingCancellationRepository(
            finalizationStarted = finalizationStarted,
            allowFinalization = allowFinalization,
        )
        launcherFixture(repository).use { fixture ->
            val bodyStarted = CompletableDeferred<Unit>()
            val job = fixture.launcher.launchRegistered(
                execution = fixture.execution,
                eventSink = fixture.eventSink,
            ) {
                bodyStarted.complete(Unit)
                awaitCancellation()
            }
            withTimeout(5_000) { bodyStarted.await() }

            assertTrue(fixture.launcher.cancel(fixture.execution.id))
            withTimeout(5_000) { finalizationStarted.await() }

            assertTrue(fixture.registry.contains(fixture.execution.id))
            assertFalse(job.isCompleted)

            allowFinalization.complete(Unit)
            withTimeout(5_000) { job.join() }

            assertEquals(AgentExecutionStatus.CANCELLED, fixture.storedExecution().status)
            assertFalse(fixture.registry.contains(fixture.execution.id))
        }
    }
}

private class LauncherFixture(
    val launcher: AgentExecutionLauncher,
    val registry: ActiveExecutionJobRegistry,
    val execution: AgentExecution,
    val eventSink: BackendAgentRuntimeEventSink,
    private val executionRepository: AgentExecutionRepository,
    private val chat: Chat,
    private val scope: CoroutineScope,
    private val dispatcher: java.io.Closeable?,
) : AutoCloseable {
    suspend fun storedExecution(): AgentExecution =
        requireNotNull(executionRepository.getByChat(chat.userId, chat.id, execution.id))

    override fun close() {
        scope.cancel()
        dispatcher?.close()
    }
}

private suspend fun launcherFixture(
    executionRepository: AgentExecutionRepository = MemoryAgentExecutionRepository(),
    scope: CoroutineScope? = null,
): LauncherFixture {
    val dispatcher = if (scope == null) Executors.newFixedThreadPool(4).asCoroutineDispatcher() else null
    val executionScope = scope ?: CoroutineScope(SupervisorJob() + requireNotNull(dispatcher))
    val registry = ActiveExecutionJobRegistry()
    val chatRepository = MemoryChatRepository()
    val messageRepository = MemoryMessageRepository()
    val optionRepository = MemoryOptionRepository()
    val eventRepository = MemoryAgentEventRepository()
    val toolCallRepository = MemoryToolCallRepository()
    val chat = Chat(
        id = UUID.randomUUID(),
        userId = "launcher-test-user",
        title = "launcher test",
        archived = false,
        createdAt = Instant.parse("2026-08-13T00:00:00Z"),
        updatedAt = Instant.parse("2026-08-13T00:00:00Z"),
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
        clientMessageId = null,
        model = null,
        provider = null,
        startedAt = Instant.parse("2026-08-13T00:00:01Z"),
        finishedAt = null,
        cancelRequested = false,
        errorCode = null,
        errorMessage = null,
        usage = null,
        metadata = emptyMap(),
    )
    executionRepository.create(execution)
    val eventService = AgentEventService(
        chatRepository = chatRepository,
        eventRepository = eventRepository,
        eventBus = AgentEventBus(),
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
    )
    val finalizer = AgentExecutionFinalizer(
        agentStateRepository = MemoryAgentStateRepository(),
        chatRepository = chatRepository,
        executionRepository = executionRepository,
        turnRunner = LauncherNeverUsedTurnRunner,
    )
    return LauncherFixture(
        launcher = AgentExecutionLauncher(
            executionScope = executionScope,
            finalizer = finalizer,
            activeJobs = registry,
        ),
        registry = registry,
        execution = execution,
        eventSink = eventSink,
        executionRepository = executionRepository,
        chat = chat,
        scope = executionScope,
        dispatcher = dispatcher,
    )
}

private class BlockingCancellationRepository(
    private val finalizationStarted: CompletableDeferred<Unit>,
    private val allowFinalization: CompletableDeferred<Unit>,
    private val delegate: MemoryAgentExecutionRepository = MemoryAgentExecutionRepository(),
) : AgentExecutionRepository by delegate {
    override suspend fun update(execution: AgentExecution): AgentExecution {
        if (execution.status == AgentExecutionStatus.CANCELLED) {
            finalizationStarted.complete(Unit)
            allowFinalization.await()
        }
        return delegate.update(execution)
    }
}

private object LauncherNeverUsedTurnRunner : BackendConversationTurnRunner {
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
            locale = "en-US",
            timeZone = "UTC",
            basedOnMessageSeq = 1,
            rowVersion = 0,
        ),
    )
}
