package ru.souz.backend.agent.runtime.conversation

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CoroutineScope
import ru.souz.agent.AgentExecutionKernelFactory
import ru.souz.agent.knowledge.ConversationKnowledgeStore
import ru.souz.agent.skills.registry.SkillBundleProvider
import ru.souz.agent.spi.AgentTelemetry
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.backend.agent.runtime.BackendAgentErrorMessages
import ru.souz.backend.agent.runtime.BackendNoopAgentDesktopInfoRepository
import ru.souz.backend.agent.runtime.BackendNoopAgentToolCatalog
import ru.souz.backend.agent.runtime.BackendNoopDefaultBrowserProvider
import ru.souz.backend.agent.runtime.BackendRequestRuntimeEnvironment
import ru.souz.backend.agent.runtime.CumulativeUsageTrackingChatApi
import ru.souz.backend.agent.session.AgentSessionRepository
import ru.souz.backend.config.BackendExecutionSettings
import ru.souz.backend.config.BackendSettingsConfig
import ru.souz.backend.llm.BackendLlmExecutionContext
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.tool.RuntimePassThroughToolsFilter
import ru.souz.tool.skills.SkillCommandRunner
import ru.souz.tool.skills.ToolGetSkillByName
import ru.souz.tool.skills.ToolGetSkillsByCategory
import ru.souz.tool.skills.ToolGetSkillsNamesByCategory
import ru.souz.tool.skills.ToolInvokeSkill

/** Builds a request-scoped backend runtime on top of the shared agent kernel. */
class BackendConversationRuntimeFactory(
    private val baseSettings: BackendSettingsConfig,
    private val llmApiFactory: suspend (BackendLlmExecutionContext) -> LLMChatAPI,
    private val sessionRepository: AgentSessionRepository,
    private val logObjectMapper: ObjectMapper,
    private val systemPrompt: String,
    private val toolCatalog: AgentToolCatalog = BackendNoopAgentToolCatalog,
    private val clientToolCatalog: AgentToolCatalog,
    private val clientSkillBundleProvider: SkillBundleProvider,
    private val userSkillBundleProvider: SkillBundleProvider,
    private val commandExecutor: SkillCommandRunner,
    private val getKnowledgeTool: LLMToolSetup,
    private val searchKnowledgeTool: LLMToolSetup,
    private val searchMemoryTool: LLMToolSetup,
    private val knowledgeStore: ConversationKnowledgeStore,
    private val agentBackgroundScope: CoroutineScope,
) {
    internal suspend fun create(
        key: AgentConversationKey,
        request: BackendConversationTurnRequest,
        initialUsage: LLMResponse.Usage = LLMResponse.Usage(0, 0, 0, 0),
    ): BackendConversationRuntime {
        val persistedSession = sessionRepository.load(key)
        val settings = BackendExecutionSettings(
            deployment = baseSettings,
            defaultSystemPrompt = request.systemPrompt ?: systemPrompt,
            locale = persistedSession?.locale ?: request.locale,
            useFewShotExamples = request.useFewShotExamples ?: baseSettings.useFewShotExamples,
            requestTimeoutMillis = request.requestTimeoutMillis ?: baseSettings.requestTimeoutMillis,
        )
        val activeClientToolCatalog = if (request.clientToolsEnabled) {
            clientToolCatalog
        } else {
            BackendNoopAgentToolCatalog
        }
        val activeSkillBundleProvider = if (request.clientToolsEnabled) {
            clientSkillBundleProvider
        } else {
            userSkillBundleProvider
        }
        val executionToolCatalog = BackendExecutionToolCatalog(
            compiledToolCatalog = toolCatalog,
            enabledCompiledToolNames = request.enabledTools,
            clientToolCatalog = activeClientToolCatalog,
            includeFewShotExamples = settings.useFewShotExamples,
        )
        val requestToolsFilter = RuntimePassThroughToolsFilter
        val delegateApi = llmApiFactory(
            BackendLlmExecutionContext(
                userId = key.userId,
                executionId = request.executionId ?: key.conversationId,
                settingsProvider = settings,
            )
        )
        val usageTrackingApi = CumulativeUsageTrackingChatApi(
            delegate = delegateApi,
            initialUsage = initialUsage,
        )
        val getSkillByNameTool = ToolGetSkillByName(
            toolCatalog = executionToolCatalog,
            toolsFilter = requestToolsFilter,
            skillBundleProvider = activeSkillBundleProvider,
            approvalGate = null,
            fileSkillsExecutable = false,
        )
        val getSkillsNamesByCategoryTool = ToolGetSkillsNamesByCategory(
            toolCatalog = executionToolCatalog,
            toolsFilter = requestToolsFilter,
        )
        val getSkillsByCategoryTool = ToolGetSkillsByCategory(
            getSkillByName = getSkillByNameTool,
            getSkillsNamesByCategory = getSkillsNamesByCategoryTool,
        )
        val runtimeCommandTool = ToolInvokeSkill(
            toolCatalog = executionToolCatalog,
            toolsFilter = requestToolsFilter,
            skillBundleProvider = activeSkillBundleProvider,
            commandExecutor = commandExecutor,
            approvalGate = null,
        )
        val kernel = AgentExecutionKernelFactory(
            logObjectMapper = logObjectMapper,
            settingsProvider = settings,
            desktopInfoRepository = BackendNoopAgentDesktopInfoRepository,
            toolCatalog = executionToolCatalog,
            toolsFilter = requestToolsFilter,
            skillBundleProvider = activeSkillBundleProvider,
            defaultBrowserProvider = BackendNoopDefaultBrowserProvider,
            runtimeEnvironment = BackendRequestRuntimeEnvironment(
                localeTag = request.locale,
                timeZone = request.timeZone,
            ),
            getSkillByNameTool = getSkillByNameTool,
            getSkillsByCategoryTool = getSkillsByCategoryTool,
            getSkillsNamesByCategoryTool = getSkillsNamesByCategoryTool,
            getKnowledgeTool = getKnowledgeTool,
            searchKnowledgeTool = searchKnowledgeTool,
            searchMemoryTool = searchMemoryTool,
            runtimeCommandTool = runtimeCommandTool,
            knowledgeStore = knowledgeStore,
            telemetry = AgentTelemetry.NONE,
            errorMessages = BackendAgentErrorMessages,
            llmApi = usageTrackingApi,
            captureScope = agentBackgroundScope,
        ).create()
        return BackendConversationRuntime(
            key = key,
            sessionRepository = sessionRepository,
            settings = settings,
            contextFactory = kernel.contextFactory,
            executor = kernel.executor,
            usageTrackingApi = usageTrackingApi,
            persistedSession = persistedSession,
        )
    }
}
