package ru.souz.backend.agent.runtime.conversation

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CoroutineScope
import ru.souz.agent.AgentExecutionKernelFactory
import ru.souz.agent.AgentId
import ru.souz.agent.knowledge.ConversationKnowledgeStore
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.skills.validation.SkillApprovalGate
import ru.souz.agent.spi.AgentTelemetry
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.backend.agent.runtime.BackendAgentErrorMessages
import ru.souz.backend.agent.runtime.BackendConversationSettingsProvider
import ru.souz.backend.agent.runtime.BackendFewShotAwareToolCatalog
import ru.souz.backend.agent.runtime.BackendNoopAgentDesktopInfoRepository
import ru.souz.backend.agent.runtime.BackendNoopAgentToolCatalog
import ru.souz.backend.agent.runtime.BackendNoopDefaultBrowserProvider
import ru.souz.backend.agent.runtime.BackendNoopMcpToolProvider
import ru.souz.backend.agent.runtime.BackendRequestRuntimeEnvironment
import ru.souz.backend.agent.runtime.BackendRequestToolCatalog
import ru.souz.backend.agent.runtime.BackendRequestToolsFilter
import ru.souz.backend.agent.runtime.BackendSkillCoreToolsFactory
import ru.souz.backend.agent.runtime.CumulativeUsageTrackingChatApi
import ru.souz.backend.agent.session.AgentSessionRepository
import ru.souz.backend.llm.BackendLlmExecutionContext
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.json.JsonUtils
import ru.souz.llms.restJsonMapper
import ru.souz.llms.runtime.ApiClassifier
import ru.souz.tool.LocalRegexClassifier
import ru.souz.tool.skills.ToolGetSkillsByCategory

/** Builds a request-scoped backend runtime on top of the shared agent kernel. */
class BackendConversationRuntimeFactory(
    private val baseSettingsProvider: SettingsProvider,
    private val llmApiFactory: suspend (BackendLlmExecutionContext) -> LLMChatAPI,
    private val sessionRepository: AgentSessionRepository,
    private val logObjectMapper: ObjectMapper,
    private val systemPrompt: String,
    private val configuredAgentId: AgentId = AgentId.default,
    private val toolCatalog: AgentToolCatalog = BackendNoopAgentToolCatalog,
    private val clientToolCatalogProvider: suspend (String) -> AgentToolCatalog = { BackendNoopAgentToolCatalog },
    private val skillRegistryRepository: SkillRegistryRepository? = null,
    private val skillCoreToolsFactory: BackendSkillCoreToolsFactory,
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
        val settingsProvider = BackendConversationSettingsProvider(
            delegate = baseSettingsProvider,
            defaultSystemPrompt = request.systemPrompt ?: systemPrompt,
            locale = persistedSession?.locale ?: request.locale,
            useFewShotExamples = request.useFewShotExamples ?: baseSettingsProvider.useFewShotExamples,
            requestTimeoutMillis = request.requestTimeoutMillis ?: baseSettingsProvider.requestTimeoutMillis,
        )
        settingsProvider.activeAgentId = persistedSession?.activeAgentId ?: configuredAgentId
        val clientToolCatalog = if (request.clientToolsEnabled) {
            clientToolCatalogProvider(key.userId)
        } else {
            BackendNoopAgentToolCatalog
        }
        val executionToolCatalog = BackendMergedToolCatalog(toolCatalog, clientToolCatalog)
        val requestScopedToolCatalog = BackendFewShotAwareToolCatalog(
            delegate = executionToolCatalog,
            settingsProvider = settingsProvider,
        )
        val enabledTools = request.enabledTools?.plus(
            clientToolCatalog.toolsByCategory.values.flatMap { it.keys }
        )
        val requestToolsFilter = BackendRequestToolsFilter(enabledTools)
        val filteredToolCatalog = BackendRequestToolCatalog(
            delegate = requestScopedToolCatalog,
            toolsFilter = requestToolsFilter,
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
        val effectiveSkillRegistryRepository = skillRegistryRepository ?: BackendNoopSkillRegistryRepository
        val skillApprovalGate = SkillApprovalGate.from(
            validationStore = effectiveSkillRegistryRepository,
            llmApi = usageTrackingApi,
            settingsProvider = settingsProvider,
            jsonUtils = JsonUtils(restJsonMapper),
        )
        val getSkillByNameTool = skillCoreToolsFactory.createGetSkillByName(
            toolCatalog = requestScopedToolCatalog,
            toolsFilter = requestToolsFilter,
            approvalGate = skillApprovalGate,
        )
        val getSkillsNamesByCategoryTool = skillCoreToolsFactory.createGetSkillsNamesByCategory(
            toolCatalog = requestScopedToolCatalog,
            toolsFilter = requestToolsFilter,
        )
        val getSkillsByCategoryTool = ToolGetSkillsByCategory(
            getSkillByName = getSkillByNameTool,
            getSkillsNamesByCategory = getSkillsNamesByCategoryTool,
        )
        val runtimeCommandTool = skillCoreToolsFactory.createRuntimeCommand(
            toolCatalog = requestScopedToolCatalog,
            toolsFilter = requestToolsFilter,
            approvalGate = skillApprovalGate,
        )
        val kernel = AgentExecutionKernelFactory(
            logObjectMapper = logObjectMapper,
            settingsProvider = settingsProvider,
            desktopInfoRepository = BackendNoopAgentDesktopInfoRepository,
            toolCatalog = filteredToolCatalog,
            toolsFilter = requestToolsFilter,
            defaultBrowserProvider = BackendNoopDefaultBrowserProvider,
            runtimeEnvironment = BackendRequestRuntimeEnvironment(
                localeTag = request.locale,
                timeZone = request.timeZone,
            ),
            mcpToolProvider = BackendNoopMcpToolProvider,
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
            apiClassifier = ApiClassifier(delegateApi),
            localClassifier = LocalRegexClassifier,
            skillRegistryRepository = effectiveSkillRegistryRepository,
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
