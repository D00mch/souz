package ru.souz.backend.agent.runtime.conversation

import ru.souz.agent.AgentContextFactory
import ru.souz.agent.AgentExecutor
import ru.souz.agent.AgentId
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.backend.config.BackendExecutionSettings
import ru.souz.backend.agent.runtime.CumulativeUsageTrackingChatApi
import ru.souz.backend.agent.session.AgentConversationSession
import ru.souz.backend.agent.session.AgentSessionRepository
import ru.souz.llms.LLMResponse
import ru.souz.llms.ToolInvocationMeta

/** Request-scoped backend conversation runtime rebuilt from the stored snapshot. */
internal class BackendConversationRuntime(
    private val key: AgentConversationKey,
    private val sessionRepository: AgentSessionRepository,
    private val settings: BackendExecutionSettings,
    private val contextFactory: AgentContextFactory,
    private val executor: AgentExecutor,
    private val usageTrackingApi: CumulativeUsageTrackingChatApi,
    private val persistedSession: AgentConversationSession?,
) {
    private val currentTemperature = persistedSession?.temperature ?: settings.temperature

    init {
        persistedSession?.let { session ->
            settings.restore(
                temperature = currentTemperature,
                locale = session.locale,
            )
        }
    }

    internal suspend fun execute(
        request: BackendConversationTurnRequest,
        persistSession: Boolean = true,
        eventSink: AgentRuntimeEventSink? = null,
        onActiveRunReady: suspend () -> Unit = {},
    ): BackendConversationExecution {
        settings.applyRequest(
            request = request,
            temperature = currentTemperature,
        )

        val seedContext = contextFactory.create(
            agentId = AgentId.SKILLS_GRAPH,
            history = persistedSession?.history.orEmpty(),
            model = settings.gigaModel,
            contextSize = request.contextSize,
            temperature = settings.temperature,
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
            basedOnMessageSeq = persistedSession?.basedOnMessageSeq ?: 0L,
            rowVersion = persistedSession?.rowVersion ?: 0L,
        )

        if (persistSession) {
            sessionRepository.save(key, nextSession)
        }

        return BackendConversationExecution(
            output = result.output,
            usage = usageTrackingApi.cumulativeUsage(),
            session = nextSession,
        )
    }

    internal fun currentUsage(): LLMResponse.Usage = usageTrackingApi.cumulativeUsage()

    internal suspend fun submitToActiveRun(input: String): Boolean =
        executor.submitToActiveRun(AgentId.SKILLS_GRAPH, input)

    internal suspend fun submitToActiveRunAfter(input: String, beforePublish: suspend () -> Boolean): Boolean =
        executor.submitToActiveRunAfter(AgentId.SKILLS_GRAPH, input, beforePublish)
}
