package ru.souz.backend.agent.runtime

import com.fasterxml.jackson.databind.ObjectMapper
import ru.souz.agent.AgentContextFactory
import ru.souz.agent.AgentExecutionKernelFactory
import ru.souz.agent.AgentExecutor
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.agent.spi.AgentTelemetry
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.backend.agent.session.AgentConversationSession
import ru.souz.backend.agent.session.AgentSessionRepository
import ru.souz.backend.llm.BackendLlmExecutionContext
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.runtime.ApiClassifier
import ru.souz.tool.LocalRegexClassifier

/** Result of one backend agent execution turn plus final usage data. */
internal data class BackendConversationExecution(
    val output: String,
    val usage: LLMResponse.Usage,
    val session: AgentConversationSession,
)

/** Request-scoped backend conversation runtime rebuilt from the stored snapshot. */
internal class BackendConversationRuntime(
    private val key: AgentConversationKey,
    private val sessionRepository: AgentSessionRepository,
    private val settingsProvider: BackendConversationSettingsProvider,
    private val contextFactory: AgentContextFactory,
    private val executor: AgentExecutor,
    private val usageTrackingApi: CumulativeUsageTrackingChatApi,
    private val persistedSession: AgentConversationSession?,
) {
    private val activeAgentId = contextFactory.normalizeAgentId(
        persistedSession?.activeAgentId ?: settingsProvider.activeAgentId
    )
    private val currentTemperature = persistedSession?.temperature ?: settingsProvider.temperature

    init {
        persistedSession?.let { session ->
            settingsProvider.restore(
                activeAgentId = activeAgentId,
                temperature = currentTemperature,
                locale = session.locale,
            )
        }
    }

    internal suspend fun execute(
        request: BackendConversationTurnRequest,
        persistSession: Boolean = true,
        eventSink: AgentRuntimeEventSink? = null,
    ): BackendConversationExecution {
        settingsProvider.applyRequest(
            request = request,
            activeAgentId = activeAgentId,
            temperature = currentTemperature,
        )

        val seedContext = contextFactory.create(
            agentId = activeAgentId,
            history = persistedSession?.history.orEmpty(),
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
            agentId = activeAgentId,
            context = seedContext,
            input = request.prompt,
            eventSink = eventSink,
        )
        val nextAgentId = contextFactory.normalizeAgentId(settingsProvider.activeAgentId)
        val nextSession = AgentConversationSession(
            activeAgentId = nextAgentId,
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
}

/** Builds a request-scoped backend runtime on top of the shared agent kernel. */
class BackendConversationRuntimeFactory(
    private val baseSettingsProvider: SettingsProvider,
    private val llmApiFactory: suspend (BackendLlmExecutionContext) -> LLMChatAPI,
    private val sessionRepository: AgentSessionRepository,
    private val logObjectMapper: ObjectMapper,
    private val systemPrompt: String,
    private val toolCatalog: AgentToolCatalog = BackendNoopAgentToolCatalog,
    private val toolsFilter: AgentToolsFilter = BackendNoopAgentToolsFilter,
    private val skillCommandTool: LLMToolSetup? = null,
    private val skillRegistryRepository: SkillRegistryRepository,
) {
    internal suspend fun create(
        key: AgentConversationKey,
        request: BackendConversationTurnRequest,
        initialUsage: LLMResponse.Usage = LLMResponse.Usage(0, 0, 0, 0),
    ): BackendConversationRuntime {
        val persistedSession = sessionRepository.load(key)
        val settingsProvider = BackendConversationSettingsProvider(
            delegate = baseSettingsProvider,
            defaultSystemPrompt = request.systemPrompt ?: systemPrompt,
            locale = persistedSession?.locale ?: request.locale,
        )
        val delegateApi = llmApiFactory(
            BackendLlmExecutionContext(
                userId = key.userId,
                executionId = request.executionId ?: key.conversationId,
                settingsProvider = settingsProvider,
            )
        )
        val usageTrackingApi = CumulativeUsageTrackingChatApi(
            delegate = delegateApi,
            initialUsage = initialUsage,
        )
        val kernel = AgentExecutionKernelFactory(
            logObjectMapper = logObjectMapper,
            settingsProvider = settingsProvider,
            desktopInfoRepository = BackendNoopAgentDesktopInfoRepository,
            toolCatalog = toolCatalog,
            toolsFilter = toolsFilter,
            defaultBrowserProvider = BackendNoopDefaultBrowserProvider,
            runtimeEnvironment = BackendRequestRuntimeEnvironment(
                localeTag = request.locale,
                timeZone = request.timeZone,
            ),
            mcpToolProvider = BackendNoopMcpToolProvider,
            skillCommandTool = skillCommandTool,
            telemetry = AgentTelemetry.NONE,
            errorMessages = BackendAgentErrorMessages,
            llmApi = usageTrackingApi,
            apiClassifier = ApiClassifier(delegateApi),
            localClassifier = LocalRegexClassifier,
            skillRegistryRepository = skillRegistryRepository,
        ).create()
        return BackendConversationRuntime(
            key = key,
            sessionRepository = sessionRepository,
            settingsProvider = settingsProvider,
            contextFactory = kernel.contextFactory,
            executor = kernel.executor,
            usageTrackingApi = usageTrackingApi,
            persistedSession = persistedSession,
        )
    }
}
