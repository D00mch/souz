package ru.souz.backend.agent.runtime.conversation

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CoroutineScope
import ru.souz.agent.AgentExecutionKernelFactory
import ru.souz.agent.spi.AgentTelemetry
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.backend.agent.runtime.BackendAgentErrorMessages
import ru.souz.backend.agent.runtime.BackendConversationSettingsProvider
import ru.souz.backend.agent.runtime.BackendNoopAgentDesktopInfoRepository
import ru.souz.backend.agent.runtime.BackendNoopAgentToolCatalog
import ru.souz.backend.agent.runtime.BackendNoopDefaultBrowserProvider
import ru.souz.backend.agent.runtime.BackendNoopMcpToolProvider
import ru.souz.backend.agent.runtime.BackendRequestRuntimeEnvironment
import ru.souz.backend.agent.runtime.CumulativeUsageTrackingChatApi
import ru.souz.backend.agent.session.AgentSessionRepository
import ru.souz.backend.llm.BackendLlmExecutionContext
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMResponse
import ru.souz.llms.runtime.ApiClassifier
import ru.souz.tool.LocalRegexClassifier

/** Builds a request-scoped backend runtime on top of the shared agent kernel. */
class BackendConversationRuntimeFactory(
    private val baseSettingsProvider: SettingsProvider,
    private val llmApiFactory: suspend (BackendLlmExecutionContext) -> LLMChatAPI,
    private val sessionRepository: AgentSessionRepository,
    private val logObjectMapper: ObjectMapper,
    private val systemPrompt: String,
    private val toolCatalog: AgentToolCatalog = BackendNoopAgentToolCatalog,
    private val clientToolCatalogProvider: () -> AgentToolCatalog = { BackendNoopAgentToolCatalog },
    private val agentBackgroundScope: CoroutineScope,
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
            useFewShotExamples = request.useFewShotExamples ?: baseSettingsProvider.useFewShotExamples,
            requestTimeoutMillis = request.requestTimeoutMillis ?: baseSettingsProvider.requestTimeoutMillis,
        )
        val clientToolCatalog = if (request.clientToolsEnabled) {
            clientToolCatalogProvider()
        } else {
            BackendNoopAgentToolCatalog
        }
        val executionToolCatalog = BackendExecutionToolCatalog(
            compiledToolCatalog = toolCatalog,
            enabledCompiledToolNames = request.enabledTools,
            clientToolCatalog = clientToolCatalog,
            includeFewShotExamples = settingsProvider.useFewShotExamples,
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
            toolCatalog = executionToolCatalog,
            defaultBrowserProvider = BackendNoopDefaultBrowserProvider,
            runtimeEnvironment = BackendRequestRuntimeEnvironment(
                localeTag = request.locale,
                timeZone = request.timeZone,
            ),
            mcpToolProvider = BackendNoopMcpToolProvider,
            alwaysAvailableToolNames = executionToolCatalog.alwaysAvailableToolNames,
            telemetry = AgentTelemetry.NONE,
            errorMessages = BackendAgentErrorMessages,
            llmApi = usageTrackingApi,
            apiClassifier = ApiClassifier(delegateApi),
            localClassifier = LocalRegexClassifier,
            captureScope = agentBackgroundScope,
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
