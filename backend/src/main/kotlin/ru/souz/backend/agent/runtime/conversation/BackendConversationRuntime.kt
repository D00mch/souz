package ru.souz.backend.agent.runtime.conversation

import ru.souz.agent.AgentContextFactory
import ru.souz.agent.AgentExecutor
import ru.souz.agent.AgentId
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.backend.agent.runtime.BackendConversationSettingsProvider
import ru.souz.backend.llm.BackendExecutionLlmChatApi
import ru.souz.backend.agent.session.AgentConversationSession
import ru.souz.backend.agent.session.AgentSessionRepository
import ru.souz.backend.chat.model.ChatMessage
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.ToolInvocationMeta

/** Request-scoped backend conversation runtime rebuilt from the stored snapshot. */
internal class BackendConversationRuntime(
    private val key: AgentConversationKey,
    private val sessionRepository: AgentSessionRepository,
    private val settingsProvider: BackendConversationSettingsProvider,
    private val contextFactory: AgentContextFactory,
    private val executor: AgentExecutor,
    private val executionApi: BackendExecutionLlmChatApi,
    private val persistedSession: AgentConversationSession?,
    private val pendingAssistantMessages: List<ChatMessage>,
) {
    internal suspend fun execute(
        request: BackendConversationTurnRequest,
        persistSession: Boolean = true,
        eventSink: AgentRuntimeEventSink? = null,
        onActiveRunReady: suspend () -> Unit = {},
    ): BackendConversationExecution {
        val seedContext = contextFactory.create(
            agentId = AgentId.SKILLS_GRAPH,
            history = persistedSession?.history.orEmpty() + pendingAssistantMessages.map {
                LLMRequest.Message(role = LLMMessageRole.assistant, content = it.content)
            },
            model = settingsProvider.gigaModel,
            contextSize = request.contextSize,
            temperature = settingsProvider.temperature,
            toolInvocationMeta = ToolInvocationMeta(
                userId = key.userId,
                conversationId = key.conversationId,
                requestId = request.executionId,
                locale = request.locale,
                timeZone = request.timeZone,
            ),
        )

        val result = executor.execute(
            agentId = AgentId.SKILLS_GRAPH,
            context = seedContext,
            input = request.prompt,
            eventSink = eventSink,
            onActiveRunReady = onActiveRunReady,
        )
        val nextSession = AgentConversationSession(
            history = result.context.history,
            temperature = result.context.settings.temperature,
            locale = request.locale,
            timeZone = request.timeZone,
            basedOnMessageSeq = pendingAssistantMessages.lastOrNull()?.seq
                ?: persistedSession?.basedOnMessageSeq
                ?: 0L,
            rowVersion = persistedSession?.rowVersion ?: 0L,
        )

        if (persistSession) {
            sessionRepository.save(key, nextSession)
        }

        return BackendConversationExecution(
            output = result.output,
            usage = executionApi.cumulativeUsage(),
            session = nextSession,
        )
    }

    internal suspend fun currentUsage(): LLMResponse.Usage = executionApi.cumulativeUsage()

    internal suspend fun submitToActiveRun(input: String): Boolean =
        executor.submitToActiveRun(AgentId.SKILLS_GRAPH, input)

    internal suspend fun submitToActiveRunAfter(input: String, beforePublish: suspend () -> Boolean): Boolean =
        executor.submitToActiveRunAfter(AgentId.SKILLS_GRAPH, input, beforePublish)
}
