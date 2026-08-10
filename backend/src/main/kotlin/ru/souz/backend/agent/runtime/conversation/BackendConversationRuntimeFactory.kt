package ru.souz.backend.agent.runtime.conversation

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CoroutineScope
import ru.souz.agent.AgentExecutionKernelFactory
import ru.souz.agent.knowledge.ConversationKnowledgeStore
import ru.souz.agent.skills.registry.SkillBundleProvider
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.spi.AgentTelemetry
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.backend.agent.runtime.BackendAgentErrorMessages
import ru.souz.backend.agent.runtime.BackendConversationSettingsProvider
import ru.souz.backend.agent.runtime.BackendNoopAgentDesktopInfoRepository
import ru.souz.backend.agent.runtime.BackendNoopAgentToolCatalog
import ru.souz.backend.agent.runtime.BackendNoopDefaultBrowserProvider
import ru.souz.backend.agent.runtime.BackendRequestRuntimeEnvironment
import ru.souz.backend.agent.runtime.CumulativeUsageTrackingChatApi
import ru.souz.backend.agent.session.AgentSessionRepository
import ru.souz.backend.llm.BackendLlmExecutionContext
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.tool.ToolCategory
import ru.souz.tool.skills.SkillCommandExecutor
import ru.souz.tool.skills.ToolGetSkillByName
import ru.souz.tool.skills.ToolGetSkillsByCategory
import ru.souz.tool.skills.ToolGetSkillsNamesByCategory
import ru.souz.tool.skills.ToolInvokeSkill

/** Builds a request-scoped backend runtime on top of the shared agent kernel. */
class BackendConversationRuntimeFactory(
    private val baseSettingsProvider: SettingsProvider,
    private val llmApiFactory: suspend (BackendLlmExecutionContext) -> LLMChatAPI,
    private val sessionRepository: AgentSessionRepository,
    private val logObjectMapper: ObjectMapper,
    private val systemPrompt: String,
    private val toolCatalog: AgentToolCatalog = BackendNoopAgentToolCatalog,
    private val clientToolCatalog: AgentToolCatalog,
    private val clientSkillBundleProvider: SkillBundleProvider,
    private val skillRegistryRepository: SkillRegistryRepository,
    private val commandExecutor: SkillCommandExecutor,
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
        val activeClientToolCatalog = if (request.clientToolsEnabled) {
            clientToolCatalog
        } else {
            BackendNoopAgentToolCatalog
        }
        val executionToolCatalog = BackendExecutionToolCatalog(
            compiledToolCatalog = toolCatalog,
            enabledCompiledToolNames = request.enabledTools,
            clientToolCatalog = activeClientToolCatalog,
            includeFewShotExamples = settingsProvider.useFewShotExamples,
        )
        val skillBundleProvider = CompositeSkillBundleProvider(
            providers = buildList {
                if (request.clientToolsEnabled) add(clientSkillBundleProvider)
                add(skillRegistryRepository)
            }
        )
        val requestToolsFilter = requestPassThroughToolsFilter()
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
        val getSkillByNameTool = ToolGetSkillByName(
            toolCatalog = executionToolCatalog,
            toolsFilter = requestToolsFilter,
            skillBundleProvider = skillBundleProvider,
            approvalGate = null,
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
            skillBundleProvider = skillBundleProvider,
            commandExecutor = commandExecutor,
            approvalGate = null,
        )
        val kernel = AgentExecutionKernelFactory(
            logObjectMapper = logObjectMapper,
            settingsProvider = settingsProvider,
            desktopInfoRepository = BackendNoopAgentDesktopInfoRepository,
            toolCatalog = executionToolCatalog,
            toolsFilter = requestToolsFilter,
            skillBundleProvider = skillBundleProvider,
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
            settingsProvider = settingsProvider,
            contextFactory = kernel.contextFactory,
            executor = kernel.executor,
            usageTrackingApi = usageTrackingApi,
            persistedSession = persistedSession,
        )
    }
}

private fun requestPassThroughToolsFilter(): AgentToolsFilter = object : AgentToolsFilter {
    override fun applyFilter(
        toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>>,
    ): Map<ToolCategory, Map<String, LLMToolSetup>> = toolsByCategory
}
