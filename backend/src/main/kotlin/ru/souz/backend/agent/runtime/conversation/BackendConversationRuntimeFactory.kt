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
import ru.souz.backend.agent.runtime.BackendExecutionLlmToolCatalog
import ru.souz.backend.agent.runtime.BackendFewShotAwareToolCatalog
import ru.souz.backend.agent.runtime.BackendNoopAgentDesktopInfoRepository
import ru.souz.backend.agent.runtime.BackendNoopAgentToolCatalog
import ru.souz.backend.agent.runtime.BackendNoopDefaultBrowserProvider
import ru.souz.backend.agent.runtime.BackendNoopMcpToolProvider
import ru.souz.backend.agent.runtime.BackendRequestRuntimeEnvironment
import ru.souz.backend.agent.runtime.BackendRequestToolCatalog
import ru.souz.backend.agent.runtime.BackendRequestToolsFilter
import ru.souz.backend.agent.session.AgentSessionRepository
import ru.souz.backend.app.BackendProviderRetryPolicy
import ru.souz.backend.llm.BackendExecutionLlmChatApi
import ru.souz.backend.llm.ProviderCredentialResolver
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.LlmProvider
import ru.souz.llms.anthropic.AnthropicVisionGateway
import ru.souz.llms.codex.CodexOAuthService
import ru.souz.llms.http.ProviderHttpClients
import ru.souz.llms.json.JsonUtils
import ru.souz.llms.local.LocalChatAPI
import ru.souz.llms.local.LocalVisionGateway
import ru.souz.llms.openai.OpenAIImageGenerationGateway
import ru.souz.llms.openai.OpenAIVisionGateway
import ru.souz.llms.restJsonMapper
import ru.souz.llms.runtime.ApiClassifier
import ru.souz.llms.runtime.LLMCapabilityResolver
import ru.souz.runtime.files.FilesToolUtil
import ru.souz.tool.LocalRegexClassifier
import ru.souz.tool.skills.ToolGetSkillsByCategory
import ru.souz.tool.skills.ToolGetSkillByName
import ru.souz.tool.skills.ToolGetSkillsNamesByCategory
import ru.souz.tool.skills.ToolInvokeSkill
import ru.souz.tool.skills.ToolRunSkillCommand
import ru.souz.tool.web.internal.WebResearchClient

/** Builds a request-scoped backend runtime on top of the shared agent kernel. */
internal class BackendConversationRuntimeFactory(
    private val baseSettingsProvider: SettingsProvider,
    private val credentialResolver: ProviderCredentialResolver,
    private val retryPolicy: BackendProviderRetryPolicy,
    private val providerHttpClients: ProviderHttpClients,
    private val localChatApi: LocalChatAPI,
    private val codexOAuthService: CodexOAuthService,
    private val sessionRepository: AgentSessionRepository,
    private val logObjectMapper: ObjectMapper,
    private val systemPrompt: String,
    private val configuredAgentId: AgentId = AgentId.default,
    private val toolCatalog: AgentToolCatalog = BackendNoopAgentToolCatalog,
    private val clientToolCatalog: AgentToolCatalog = BackendNoopAgentToolCatalog,
    private val skillRegistryRepository: SkillRegistryRepository? = null,
    private val legacyCommandTool: LLMToolSetup,
    private val commandTool: ToolRunSkillCommand,
    private val filesToolUtil: FilesToolUtil,
    private val webResearchClient: WebResearchClient,
    private val getKnowledgeTool: LLMToolSetup,
    private val searchKnowledgeTool: LLMToolSetup,
    private val searchMemoryTool: LLMToolSetup,
    private val knowledgeStore: ConversationKnowledgeStore,
    private val agentBackgroundScope: CoroutineScope,
    private val testLlmApiFactory: (suspend (SettingsProvider) -> LLMChatAPI)? = null,
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
        val activeAgentId = persistedSession?.activeAgentId ?: settingsProvider.activeAgentId
        val temperature = persistedSession?.temperature ?: settingsProvider.temperature
        settingsProvider.restore(
            activeAgentId = activeAgentId,
            temperature = temperature,
            locale = persistedSession?.locale ?: request.locale,
        )
        settingsProvider.applyRequest(
            request = request,
            activeAgentId = activeAgentId,
            temperature = temperature,
        )
        val testApi = testLlmApiFactory?.invoke(settingsProvider)
        val executionApi = BackendExecutionLlmChatApi(
            userId = key.userId,
            settingsProvider = settingsProvider,
            credentialResolver = credentialResolver,
            retryPolicy = retryPolicy,
            httpClients = providerHttpClients,
            localChatApi = localChatApi,
            codexOAuthService = codexOAuthService,
            initialUsage = initialUsage,
            providerApiOverride = testApi?.let { api -> { api } },
        )
        val requestClientToolCatalog = if (request.clientToolsEnabled) {
            clientToolCatalog
        } else {
            BackendNoopAgentToolCatalog
        }
        val visionGateway = LLMCapabilityResolver(
            settingsProvider = settingsProvider,
            openAiGateway = OpenAIVisionGateway(settingsProvider, executionApi),
            anthropicGateway = AnthropicVisionGateway(settingsProvider, executionApi),
            additionalGateways = mapOf(
                LlmProvider.LOCAL to LocalVisionGateway(settingsProvider, executionApi),
            ),
        )
        val imageGenerationGateway = OpenAIImageGenerationGateway(
            settingsProvider = settingsProvider,
            client = providerHttpClients.openAi,
            apiKeyProvider = { executionApi.credentialFor(LlmProvider.OPENAI) },
        )
        val executionLlmToolCatalog = BackendExecutionLlmToolCatalog(
            llmApi = executionApi,
            settingsProvider = settingsProvider,
            filesToolUtil = filesToolUtil,
            webResearchClient = webResearchClient,
            visionGateway = visionGateway,
            imageGenerationGateway = imageGenerationGateway,
        )
        val executionToolCatalog = BackendMergedToolCatalog(
            toolCatalog,
            executionLlmToolCatalog,
            requestClientToolCatalog,
        )
        val requestScopedToolCatalog = BackendFewShotAwareToolCatalog(
            delegate = executionToolCatalog,
            settingsProvider = settingsProvider,
        )
        val enabledTools = request.enabledTools?.plus(
            requestClientToolCatalog.toolsByCategory.values.flatMap { it.keys }
        )
        val requestToolsFilter = BackendRequestToolsFilter(enabledTools)
        val filteredToolCatalog = BackendRequestToolCatalog(
            delegate = requestScopedToolCatalog,
            toolsFilter = requestToolsFilter,
        )
        val effectiveSkillRegistryRepository = skillRegistryRepository ?: BackendNoopSkillRegistryRepository
        val skillApprovalGate = SkillApprovalGate.from(
            validationStore = effectiveSkillRegistryRepository,
            llmApi = executionApi,
            settingsProvider = settingsProvider,
            jsonUtils = JsonUtils(restJsonMapper),
        )
        val getSkillByNameTool = ToolGetSkillByName(
            toolCatalog = requestScopedToolCatalog,
            toolsFilter = requestToolsFilter,
            skillBundleProvider = effectiveSkillRegistryRepository,
            legacyCommandTool = legacyCommandTool,
            approvalGate = skillApprovalGate,
        )
        val getSkillsNamesByCategoryTool = ToolGetSkillsNamesByCategory(
            toolCatalog = requestScopedToolCatalog,
            toolsFilter = requestToolsFilter,
        )
        val getSkillsByCategoryTool = ToolGetSkillsByCategory(
            getSkillByName = getSkillByNameTool,
            getSkillsNamesByCategory = getSkillsNamesByCategoryTool,
        )
        val runtimeCommandTool = ToolInvokeSkill(
            toolCatalog = requestScopedToolCatalog,
            toolsFilter = requestToolsFilter,
            skillBundleProvider = effectiveSkillRegistryRepository,
            commandTool = commandTool,
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
            llmApi = executionApi,
            apiClassifier = ApiClassifier(executionApi),
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
            executionApi = executionApi,
            persistedSession = persistedSession,
        )
    }
}
