package ru.souz.backend.execution.service

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import ru.souz.agent.AgentId
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.backend.TestSettingsProvider
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.backend.agent.runtime.BackendConversationTurnOutcome
import ru.souz.backend.agent.runtime.BackendConversationTurnRunner
import ru.souz.backend.agent.runtime.BackendNoopAgentToolCatalog
import ru.souz.backend.agent.session.AgentConversationSession
import ru.souz.backend.agent.session.AgentConversationState
import ru.souz.backend.agent.session.AgentStateConflictException
import ru.souz.backend.agent.session.AgentStateRepository
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.chat.model.ChatRole
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.events.bus.AgentEventBus
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.http.BackendV1Exception
import ru.souz.backend.settings.repository.UserSettingsRepository
import ru.souz.backend.settings.service.EffectiveSettingsResolver
import ru.souz.backend.storage.memory.MemoryAgentEventRepository
import ru.souz.backend.storage.memory.MemoryAgentExecutionRepository
import ru.souz.backend.storage.memory.MemoryChatRepository
import ru.souz.backend.storage.memory.MemoryOptionRepository
import ru.souz.backend.storage.memory.MemoryMessageRepository
import ru.souz.backend.storage.memory.MemoryToolCallRepository
import ru.souz.backend.storage.memory.MemoryUserProviderKeyRepository
import ru.souz.backend.storage.memory.MemoryUserSettingsRepository
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LocalModelAvailability
import ru.souz.llms.LLMRequest

class AgentExecutionServiceStateConflictTest {
    @Test
    fun `state conflict marks execution failed and preserves stored context`() = runTest {
        val chatRepository = MemoryChatRepository()
        val messageRepository = MemoryMessageRepository()
        val executionRepository = MemoryAgentExecutionRepository()
        val optionRepository = MemoryOptionRepository()
        val eventRepository = MemoryAgentEventRepository()
        val userSettingsRepository = MemoryUserSettingsRepository()
        val stateRepository = ConflictOnSaveAgentStateRepository(
            persistedState = AgentConversationState(
                userId = "user-state-conflict",
                chatId = UUID.randomUUID(),
                schemaVersion = 1,
                activeAgentId = AgentId.default,
                history = listOf(
                    LLMRequest.Message(
                        role = LLMMessageRole.user,
                        content = "persisted-state",
                    )
                ),
                temperature = 0.4f,
                locale = Locale.forLanguageTag("ru-RU"),
                timeZone = ZoneId.of("Europe/Moscow"),
                basedOnMessageSeq = 1L,
                updatedAt = Instant.parse("2026-05-01T15:00:00Z"),
                rowVersion = 0L,
            )
        )
        val chat = Chat(
            id = stateRepository.chatId,
            userId = stateRepository.userId,
            title = "state-conflict",
            archived = false,
            createdAt = Instant.parse("2026-05-01T14:00:00Z"),
            updatedAt = Instant.parse("2026-05-01T14:00:00Z"),
        )
        chatRepository.create(chat)

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
        val featureFlags = BackendFeatureFlags()
        val userProviderKeyRepository = MemoryUserProviderKeyRepository()
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
            turnRunner = CompletedTurnRunner(
                session = AgentConversationSession(
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
                ),
            ),
            executionRepository = executionRepository,
            optionRepository = optionRepository,
            eventRepository = eventRepository,
            eventService = eventService,
            toolCallRepository = MemoryToolCallRepository(),
            featureFlags = featureFlags,
            executionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )

        val error = assertFailsWith<BackendV1Exception> {
            service.executeChatTurn(
                userId = chat.userId,
                chatId = chat.id,
                content = "generate reply",
            )
        }

        val execution = executionRepository.listByChat(chat.userId, chat.id).single()
        val failureEvent = eventRepository.listByChat(chat.userId, chat.id).last()

        assertEquals("agent_execution_failed", error.code)
        assertEquals(AgentExecutionStatus.FAILED, execution.status)
        assertEquals("state_conflict", execution.errorCode)
        assertEquals(AgentEventType.EXECUTION_FAILED, failureEvent.type)
        assertEquals("state_conflict", failureEvent.payload["errorCode"])
        assertEquals(
            listOf("persisted-state"),
            stateRepository.get(chat.userId, chat.id)?.history?.map { it.content },
        )
    }

    private fun unavailableLocalModels(): LocalModelAvailability =
        object : LocalModelAvailability {
            override fun isProviderAvailable(): Boolean = false
            override fun availableGigaModels() = emptyList<ru.souz.llms.LLMModel>()
            override fun defaultGigaModel() = null
        }
}

private class CompletedTurnRunner(
    private val session: AgentConversationSession,
) : BackendConversationTurnRunner {
    override suspend fun run(
        conversationKey: AgentConversationKey,
        request: BackendConversationTurnRequest,
        eventSink: ru.souz.agent.runtime.AgentRuntimeEventSink,
        initialUsage: ru.souz.llms.LLMResponse.Usage,
    ): BackendConversationTurnOutcome =
        BackendConversationTurnOutcome.Completed(
            output = "assistant output",
            usage = ru.souz.llms.LLMResponse.Usage(
                promptTokens = 3,
                completionTokens = 4,
                totalTokens = 7,
                precachedTokens = 0,
            ),
            session = session,
        )
}

private class ConflictOnSaveAgentStateRepository(
    private var persistedState: AgentConversationState,
) : AgentStateRepository {
    val userId: String = persistedState.userId
    val chatId: UUID = persistedState.chatId

    override suspend fun get(userId: String, chatId: UUID): AgentConversationState? =
        persistedState.takeIf { it.userId == userId && it.chatId == chatId }

    override suspend fun save(state: AgentConversationState): AgentConversationState {
        throw AgentStateConflictException(
            userId = state.userId,
            chatId = state.chatId,
            expectedRowVersion = state.rowVersion,
        )
    }
}
