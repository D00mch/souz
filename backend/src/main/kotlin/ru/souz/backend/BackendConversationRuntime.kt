package ru.souz.backend

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.concurrent.ConcurrentHashMap
import ru.souz.agent.AgentContextFactory
import ru.souz.agent.AgentExecutionKernelFactory
import ru.souz.agent.AgentExecutor
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMResponse
import ru.souz.llms.runtime.ApiClassifier
import ru.souz.tool.LocalRegexClassifier

internal data class BackendConversationExecution(
    val output: String,
    val usage: LLMResponse.Usage,
)

internal class BackendConversationRuntime(
    private val key: AgentConversationKey,
    private val sessionRepository: AgentSessionRepository,
    private val settingsProvider: BackendConversationSettingsProvider,
    private val contextFactory: AgentContextFactory,
    private val executor: AgentExecutor,
    private val usageTrackingApi: UsageTrackingChatApi,
    persistedSession: AgentConversationSession?,
) {
    private var activeAgentId = contextFactory.normalizeAgentId(
        persistedSession?.activeAgentId ?: settingsProvider.activeAgentId
    )
    private var currentContext = persistedSession?.context ?: contextFactory.create(activeAgentId)

    init {
        persistedSession?.let { session ->
            settingsProvider.restore(
                activeAgentId = activeAgentId,
                contextSize = session.context.settings.contextSize,
                temperature = session.context.settings.temperature,
                locale = session.locale,
            )
        }
    }

    internal suspend fun execute(request: ValidatedAgentRequest): BackendConversationExecution {
        settingsProvider.applyRequest(
            request = request,
            activeAgentId = activeAgentId,
            temperature = currentContext.settings.temperature,
        )
        usageTrackingApi.resetUsage()

        val seedContext = currentContext.copy(
            settings = currentContext.settings.copy(
                model = settingsProvider.gigaModel.alias,
                contextSize = request.contextSize,
                temperature = settingsProvider.temperature,
            ),
            systemPrompt = contextFactory.systemPromptFor(activeAgentId, settingsProvider.gigaModel),
        )

        val result = executor.execute(
            agentId = activeAgentId,
            context = seedContext,
            input = request.prompt,
        )
        currentContext = result.context
        activeAgentId = contextFactory.normalizeAgentId(settingsProvider.activeAgentId)

        sessionRepository.save(
            key,
            AgentConversationSession(
                activeAgentId = activeAgentId,
                context = currentContext,
                locale = request.locale,
                timeZone = request.timeZone,
            )
        )

        return BackendConversationExecution(
            output = result.output,
            usage = usageTrackingApi.latestUsage(),
        )
    }
}

class BackendConversationRuntimeFactory(
    private val baseSettingsProvider: SettingsProvider,
    private val llmApiFactory: (BackendConversationSettingsProvider) -> LLMChatAPI,
    private val sessionRepository: AgentSessionRepository,
    private val logObjectMapper: ObjectMapper,
    private val systemPrompt: String,
) {
    internal suspend fun create(
        key: AgentConversationKey,
        request: ValidatedAgentRequest,
    ): BackendConversationRuntime {
        val persistedSession = sessionRepository.load(key)
        val settingsProvider = BackendConversationSettingsProvider(
            delegate = baseSettingsProvider,
            systemPrompt = systemPrompt,
            locale = persistedSession?.locale ?: request.locale,
        )
        val usageTrackingApi = UsageTrackingChatApi(llmApiFactory(settingsProvider))
        val kernel = AgentExecutionKernelFactory(
            logObjectMapper = logObjectMapper,
            settingsProvider = settingsProvider,
            desktopInfoRepository = BackendNoopAgentDesktopInfoRepository,
            toolCatalog = BackendNoopAgentToolCatalog,
            toolsFilter = BackendNoopAgentToolsFilter,
            defaultBrowserProvider = BackendNoopDefaultBrowserProvider,
            mcpToolProvider = BackendNoopMcpToolProvider,
            telemetry = BackendNoopAgentTelemetry,
            errorMessages = BackendAgentErrorMessages,
            llmApi = usageTrackingApi,
            apiClassifier = ApiClassifier(usageTrackingApi),
            localClassifier = LocalRegexClassifier,
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

class BackendConversationRuntimeCache(
    private val factory: BackendConversationRuntimeFactory,
) {
    private val runtimes = ConcurrentHashMap<AgentConversationKey, BackendConversationRuntime>()

    internal suspend fun getOrCreate(
        key: AgentConversationKey,
        request: ValidatedAgentRequest,
    ): BackendConversationRuntime {
        val cached = runtimes[key]
        if (cached != null) return cached

        val created = factory.create(key, request)
        return runtimes.putIfAbsent(key, created) ?: created
    }
}
