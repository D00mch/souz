package ru.souz.backend.execution.service

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import ru.souz.backend.TestSettingsProvider
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.backend.agent.runtime.BackendConversationTurnOutcome
import ru.souz.backend.agent.runtime.BackendConversationTurnRunner
import ru.souz.backend.agent.runtime.BackendNoopAgentToolCatalog
import ru.souz.backend.agent.session.AgentStateRepository
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.events.bus.AgentEventBus
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.http.BackendV1Exception
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
import ru.souz.llms.LocalModelAvailability

class AgentExecutionServiceCancellationTest {
    @Test
    fun `runner cancellation marks execution cancelled`() = runTest {
        val context = cancellationTestContext()
        try {
            val error = assertFailsWith<BackendV1Exception> {
                context.service.executeChatTurn(
                    userId = context.chat.userId,
                    chatId = context.chat.id,
                    content = "cancel turn",
                )
            }

            val execution = assertNotNull(
                context.executionRepository.getByChat(
                    context.chat.userId,
                    context.chat.id,
                    context.executionRepository.listByChat(context.chat.userId, context.chat.id).single().id,
                )
            )
            val eventTypes = context.eventRepository.listByChat(context.chat.userId, context.chat.id).map { it.type }

            assertEquals("agent_execution_cancelled", error.code)
            assertEquals(AgentExecutionStatus.CANCELLED, execution.status)
            assertEquals(AgentEventType.EXECUTION_CANCELLED, eventTypes.last())
            assertFalse(eventTypes.contains(AgentEventType.EXECUTION_FAILED))
        } finally {
            context.close()
        }
    }
}

private suspend fun cancellationTestContext(): CancellationTestContext {
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
        useStreaming = false
    }
    val featureFlags = BackendFeatureFlags(
        wsEvents = false,
        streamingMessages = false,
        toolEvents = true,
    )
    val executionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val effectiveSettingsResolver = EffectiveSettingsResolver(
        baseSettingsProvider = settingsProvider,
        userSettingsRepository = userSettingsRepository,
        userProviderKeyRepository = userProviderKeyRepository,
        featureFlags = featureFlags,
        toolCatalog = BackendNoopAgentToolCatalog,
        localModelAvailability = unavailableLocalModelsForCancellationTest(),
    )
    val toolCallRepository = MemoryToolCallRepository()
    val finalizer = AgentExecutionFinalizer(
        agentStateRepository = stateRepository,
        chatRepository = chatRepository,
        executionRepository = executionRepository,
        turnRunner = CancellingTurnRunner(),
    )
    val service = AgentExecutionService(
        chatRepository = chatRepository,
        messageRepository = messageRepository,
        executionRepository = executionRepository,
        optionRepository = optionRepository,
        eventService = eventService,
        toolCallRepository = toolCallRepository,
        requestFactory = AgentExecutionRequestFactory(
            effectiveSettingsResolver = effectiveSettingsResolver,
            featureFlags = featureFlags,
        ),
        finalizer = finalizer,
        launcher = AgentExecutionLauncher(
            executionScope = executionScope,
            finalizer = finalizer,
        ),
    )
    val chat = Chat(
        id = UUID.randomUUID(),
        userId = "user-cancel",
        title = "cancellation",
        archived = false,
        createdAt = Instant.parse("2026-05-02T09:00:00Z"),
        updatedAt = Instant.parse("2026-05-02T09:00:00Z"),
    )
    chatRepository.create(chat)
    return CancellationTestContext(
        service = service,
        chat = chat,
        executionRepository = executionRepository,
        eventRepository = eventRepository,
        executionScope = executionScope,
    )
}

private data class CancellationTestContext(
    val service: AgentExecutionService,
    val chat: Chat,
    val executionRepository: MemoryAgentExecutionRepository,
    val eventRepository: MemoryAgentEventRepository,
    val executionScope: CoroutineScope,
) : AutoCloseable {
    override fun close() {
        executionScope.cancel()
    }
}

private class CancellingTurnRunner : BackendConversationTurnRunner {
    override suspend fun run(
        conversationKey: AgentConversationKey,
        request: BackendConversationTurnRequest,
        eventSink: ru.souz.agent.runtime.AgentRuntimeEventSink,
        initialUsage: ru.souz.llms.LLMResponse.Usage,
    ): BackendConversationTurnOutcome {
        throw CancellationException("runner cancelled")
    }
}

private fun unavailableLocalModelsForCancellationTest(): LocalModelAvailability =
    object : LocalModelAvailability {
        override fun isProviderAvailable(): Boolean = false

        override fun availableGigaModels() = emptyList<ru.souz.llms.LLMModel>()

        override fun defaultGigaModel() = null
    }
