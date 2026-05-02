package ru.souz.backend.execution.service

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import ru.souz.agent.AgentId
import ru.souz.backend.TestSettingsProvider
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.backend.agent.runtime.BackendConversationTurnOutcome
import ru.souz.backend.agent.runtime.BackendConversationTurnRunner
import ru.souz.backend.agent.runtime.BackendNoopAgentToolCatalog
import ru.souz.backend.agent.session.AgentConversationSession
import ru.souz.backend.agent.session.AgentStateRepository
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.chat.model.ChatRole
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.events.bus.AgentEventBus
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.settings.service.EffectiveSettingsResolver
import ru.souz.backend.storage.memory.MemoryAgentEventRepository
import ru.souz.backend.storage.memory.MemoryAgentExecutionRepository
import ru.souz.backend.storage.memory.MemoryAgentStateRepository
import ru.souz.backend.storage.memory.MemoryChatRepository
import ru.souz.backend.storage.memory.MemoryMessageRepository
import ru.souz.backend.storage.memory.MemoryOptionRepository
import ru.souz.backend.storage.memory.MemoryToolCallRepository
import ru.souz.backend.storage.memory.MemoryUserProviderKeyRepository
import ru.souz.backend.storage.memory.MemoryUserSettingsRepository
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LocalModelAvailability

@OptIn(ExperimentalCoroutinesApi::class)
class AgentExecutionServiceAsyncLifecycleTest {
    @Test
    fun `async execution does not start background body before executeChatTurn returns`() = runTest {
        val runner = CountingCompletedTurnRunner()
        val context = asyncLifecycleContext(runner)
        try {
            val result = context.service.executeChatTurn(
                userId = context.chat.userId,
                chatId = context.chat.id,
                content = "start later",
            )

            assertEquals(0, runner.startedCount)
            assertNull(result.assistantMessage)
            assertEquals(AgentExecutionStatus.RUNNING, result.execution.status)

            advanceUntilIdle()

            val storedExecution = assertNotNull(
                context.executionRepository.getByChat(
                    context.chat.userId,
                    context.chat.id,
                    result.execution.id,
                )
            )
            assertEquals(1, runner.startedCount)
            assertEquals(AgentExecutionStatus.COMPLETED, storedExecution.status)
        } finally {
            context.close()
        }
    }

    @Test
    fun `async execution is registered before start and cancel before body begins ends as cancelled`() = runTest {
        val runner = CountingCompletedTurnRunner()
        val context = asyncLifecycleContext(runner)
        try {
            val result = context.service.executeChatTurn(
                userId = context.chat.userId,
                chatId = context.chat.id,
                content = "cancel before start",
            )

            assertEquals(0, runner.startedCount)

            val cancelling = context.service.cancelActive(
                userId = context.chat.userId,
                chatId = context.chat.id,
            )

            assertEquals(AgentExecutionStatus.CANCELLING, cancelling.execution.status)

            advanceUntilIdle()

            val storedExecution = assertNotNull(
                context.executionRepository.getByChat(
                    context.chat.userId,
                    context.chat.id,
                    result.execution.id,
                )
            )
            val eventTypes = context.eventRepository.listByChat(context.chat.userId, context.chat.id).map { it.type }
            val messages = context.messageRepository.list(context.chat.userId, context.chat.id)

            assertEquals(0, runner.startedCount)
            assertEquals(AgentExecutionStatus.CANCELLED, storedExecution.status)
            assertNull(storedExecution.assistantMessageId)
            assertEquals(listOf(ChatRole.USER), messages.map { it.role })
            assertEquals(AgentEventType.EXECUTION_CANCELLED, eventTypes.last())
            assertFalse(eventTypes.contains(AgentEventType.EXECUTION_FAILED))
        } finally {
            context.close()
        }
    }

    @Test
    fun `cancel running async execution marks cancelled and does not create assistant message`() = runTest {
        val runner = HangingTurnRunner()
        val context = asyncLifecycleContext(runner)
        try {
            val result = context.service.executeChatTurn(
                userId = context.chat.userId,
                chatId = context.chat.id,
                content = "cancel while running",
            )

            advanceUntilIdle()
            runner.awaitStarted()

            val cancelling = context.service.cancelActive(
                userId = context.chat.userId,
                chatId = context.chat.id,
            )

            assertEquals(AgentExecutionStatus.CANCELLING, cancelling.execution.status)

            advanceUntilIdle()

            val storedExecution = assertNotNull(
                context.executionRepository.getByChat(
                    context.chat.userId,
                    context.chat.id,
                    result.execution.id,
                )
            )
            val eventTypes = context.eventRepository.listByChat(context.chat.userId, context.chat.id).map { it.type }
            val messages = context.messageRepository.list(context.chat.userId, context.chat.id)

            assertEquals(AgentExecutionStatus.CANCELLED, storedExecution.status)
            assertNull(storedExecution.assistantMessageId)
            assertEquals(listOf(ChatRole.USER), messages.map { it.role })
            assertEquals(AgentEventType.EXECUTION_CANCELLED, eventTypes.last())
            assertFalse(eventTypes.contains(AgentEventType.EXECUTION_FAILED))
        } finally {
            context.close()
        }
    }
}

private suspend fun TestScope.asyncLifecycleContext(
    turnRunner: BackendConversationTurnRunner,
): AsyncLifecycleTestContext {
    val chatRepository = MemoryChatRepository()
    val messageRepository = MemoryMessageRepository()
    val executionRepository = MemoryAgentExecutionRepository()
    val optionRepository = MemoryOptionRepository()
    val eventRepository = MemoryAgentEventRepository()
    val userSettingsRepository = MemoryUserSettingsRepository()
    val userProviderKeyRepository = MemoryUserProviderKeyRepository()
    val stateRepository: AgentStateRepository = MemoryAgentStateRepository()
    val eventService = AgentEventService(
        chatRepository = chatRepository,
        eventRepository = eventRepository,
        eventBus = AgentEventBus(),
    )
    val settingsProvider = TestSettingsProvider().apply {
        gigaChatKey = "giga-key"
        contextSize = 24_000
        temperature = 0.6f
        useStreaming = true
    }
    val featureFlags = BackendFeatureFlags(
        wsEvents = true,
        streamingMessages = true,
        toolEvents = true,
    )
    val executionScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
    val service = AgentExecutionService(
        chatRepository = chatRepository,
        messageRepository = messageRepository,
        agentStateRepository = stateRepository,
        effectiveSettingsResolver = EffectiveSettingsResolver(
            baseSettingsProvider = settingsProvider,
            userSettingsRepository = userSettingsRepository,
            userProviderKeyRepository = userProviderKeyRepository,
            featureFlags = featureFlags,
            toolCatalog = BackendNoopAgentToolCatalog,
            localModelAvailability = unavailableLocalModels(),
        ),
        turnRunner = turnRunner,
        executionRepository = executionRepository,
        optionRepository = optionRepository,
        eventRepository = eventRepository,
        eventService = eventService,
        toolCallRepository = MemoryToolCallRepository(),
        featureFlags = featureFlags,
        executionScope = executionScope,
    )
    val chat = Chat(
        id = UUID.randomUUID(),
        userId = "user-async",
        title = "async-lifecycle",
        archived = false,
        createdAt = Instant.parse("2026-05-02T09:00:00Z"),
        updatedAt = Instant.parse("2026-05-02T09:00:00Z"),
    )
    chatRepository.create(chat)
    return AsyncLifecycleTestContext(
        service = service,
        chat = chat,
        messageRepository = messageRepository,
        executionRepository = executionRepository,
        eventRepository = eventRepository,
        executionScope = executionScope,
    )
}

private data class AsyncLifecycleTestContext(
    val service: AgentExecutionService,
    val chat: Chat,
    val messageRepository: MemoryMessageRepository,
    val executionRepository: MemoryAgentExecutionRepository,
    val eventRepository: MemoryAgentEventRepository,
    val executionScope: CoroutineScope,
) : AutoCloseable {
    override fun close() {
        executionScope.cancel()
    }
}

private class CountingCompletedTurnRunner : BackendConversationTurnRunner {
    var startedCount: Int = 0
        private set

    override suspend fun run(
        conversationKey: AgentConversationKey,
        request: BackendConversationTurnRequest,
        eventSink: ru.souz.agent.runtime.AgentRuntimeEventSink,
        initialUsage: LLMResponse.Usage,
    ): BackendConversationTurnOutcome {
        startedCount += 1
        return BackendConversationTurnOutcome.Completed(
            output = "assistant output",
            usage = LLMResponse.Usage(
                promptTokens = 3,
                completionTokens = 4,
                totalTokens = 7,
                precachedTokens = 0,
            ),
            session = completedSession(),
        )
    }
}

private class HangingTurnRunner : BackendConversationTurnRunner {
    private val started = CompletableDeferred<Unit>()

    suspend fun awaitStarted() {
        started.await()
    }

    override suspend fun run(
        conversationKey: AgentConversationKey,
        request: BackendConversationTurnRequest,
        eventSink: ru.souz.agent.runtime.AgentRuntimeEventSink,
        initialUsage: LLMResponse.Usage,
    ): BackendConversationTurnOutcome {
        started.complete(Unit)
        awaitCancellation()
    }
}

private fun completedSession(): AgentConversationSession =
    AgentConversationSession(
        activeAgentId = AgentId.default,
        history = listOf(
            LLMRequest.Message(
                role = LLMMessageRole.user,
                content = "updated-state",
            )
        ),
        temperature = 0.6f,
        locale = "ru-RU",
        timeZone = "Europe/Moscow",
        basedOnMessageSeq = 1L,
        rowVersion = 0L,
    )

private fun unavailableLocalModels(): LocalModelAvailability =
    object : LocalModelAvailability {
        override fun isProviderAvailable(): Boolean = false

        override fun availableGigaModels() = emptyList<ru.souz.llms.LLMModel>()

        override fun defaultGigaModel() = null
    }
