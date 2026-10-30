package ru.souz.backend.agent.runtime.conversation

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.plus
import ru.souz.ToolLoopGraphBasedAgent
import ru.souz.agent.AgentCoreTools
import ru.souz.agent.AgentExecutionKernelFactory
import ru.souz.agent.knowledge.ConversationKnowledgeStore
import ru.souz.agent.skills.registry.SkillBundleProvider
import ru.souz.agent.skills.SkillClassifier
import ru.souz.agent.spi.AgentTelemetry
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.TaskComplexityClassifier
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.backend.agent.model.chatId
import ru.souz.backend.agent.runtime.BackendAgentErrorMessages
import ru.souz.backend.agent.runtime.BackendConversationSettingsProvider
import ru.souz.backend.agent.runtime.BackendRequestRuntimeEnvironment
import ru.souz.backend.agent.session.AgentStateRepository
import ru.souz.backend.agent.session.toConversationSession
import ru.souz.backend.app.BackendProviderRetryPolicy
import ru.souz.backend.chat.repository.MessageRepository
import ru.souz.backend.chat.repository.ChatRepository
import ru.souz.backend.client.BackendClientSkills
import ru.souz.backend.client.CLIENT_TOOL_ROUTING_INSTRUCTIONS
import ru.souz.backend.client.CLIENT_TOOL_DISCOVERY_NAME
import ru.souz.backend.client.ClientError
import ru.souz.backend.client.ClientToolDiscoveryResult
import ru.souz.backend.client.DiscoveredClientTools
import ru.souz.backend.client.normalizeClientTools
import ru.souz.backend.common.BackendToolCapabilityPolicy
import ru.souz.backend.common.backendLogContext
import ru.souz.backend.hooks.HookLlmBudget
import ru.souz.backend.hooks.HookLlmBudget.Companion.withProviderCall
import ru.souz.backend.hooks.HookStore
import ru.souz.backend.config.NamedLlmConfig
import ru.souz.backend.http.clientFrameMapper
import ru.souz.backend.llm.BackendExecutionLlmChatApi
import ru.souz.backend.llm.ModelFailureMemory
import ru.souz.backend.llm.ProviderCredentialResolver
import ru.souz.backend.llm.quota.ExecutionQuotaManager
import ru.souz.backend.metrics.BackendMetrics
import ru.souz.backend.personality.personalityInstructions
import ru.souz.backend.personality.PersonalitySync
import ru.souz.backend.personality.PersonalityTool
import ru.souz.backend.settings.repository.UserSettingsRepository
import ru.souz.backend.settings.service.EffectiveSettingsResolver
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.LlmProvider
import ru.souz.llms.anthropic.AnthropicVisionGateway
import ru.souz.llms.codex.CodexOAuthService
import ru.souz.llms.http.ProviderHttpClients
import ru.souz.llms.openai.OpenAIImageGenerationGateway
import ru.souz.llms.openai.OpenAIVisionGateway
import ru.souz.llms.runtime.LLMCapabilityResolver
import ru.souz.llms.runtime.ImageGenerationGateway
import ru.souz.memory.ConversationMemoryRuntime
import ru.souz.runtime.files.FilesToolUtil
import ru.souz.tool.RuntimePassThroughToolsFilter
import ru.souz.tool.LlmBackedToolCatalog
import ru.souz.tool.ToolCategory
import ru.souz.tool.skills.SkillCommandExecutor
import ru.souz.tool.skills.ToolGetSkillByName
import ru.souz.tool.skills.ToolGetSkillsByCategory
import ru.souz.tool.skills.ToolGetSkillsNamesByCategory
import ru.souz.tool.skills.ToolInvokeSkill
import ru.souz.tool.subagent.SubagentToolFactory
import ru.souz.tool.web.internal.WebResearchClient

/** Builds a request-scoped backend runtime on top of the shared agent kernel. */
internal class BackendConversationRuntimeFactory(
    private val userSettingsRepository: UserSettingsRepository,
    private val personalitySync: PersonalitySync,
    private val baseSettingsProvider: SettingsProvider,
    private val credentialResolver: ProviderCredentialResolver,
    private val effectiveSettingsResolver: EffectiveSettingsResolver,
    private val retryPolicy: BackendProviderRetryPolicy,
    private val providerHttpClients: ProviderHttpClients,
    private val codexOAuthService: CodexOAuthService,
    private val agentStateRepository: AgentStateRepository,
    private val messageRepository: MessageRepository,
    private val logObjectMapper: ObjectMapper,
    private val systemPrompt: String,
    private val policyPrompt: String?,
    private val toolCatalog: AgentToolCatalog = AgentToolCatalog(),
    private val clientSkills: BackendClientSkills,
    private val chatRepository: ChatRepository,
    private val skillBundleProvider: SkillBundleProvider,
    private val skillClassifier: SkillClassifier,
    private val fallbackSkillClassifier: SkillClassifier? = null,
    private val complexityClassifier: TaskComplexityClassifier? = null,
    private val commandExecutor: SkillCommandExecutor,
    private val filesToolUtil: FilesToolUtil,
    private val webResearchClient: WebResearchClient,
    private val getKnowledgeTool: LLMToolSetup,
    private val searchKnowledgeTool: LLMToolSetup,
    private val searchMemoryTool: LLMToolSetup,
    private val forgetMemoryTools: List<LLMToolSetup>,
    private val knowledgeStore: ConversationKnowledgeStore,
    private val agentBackgroundScope: CoroutineScope,
    private val memoryRuntime: ConversationMemoryRuntime,
    private val automaticMemoryRecall: Boolean,
    private val hookStore: HookStore,
    private val executionQuotas: ExecutionQuotaManager,
    private val metrics: BackendMetrics? = null,
    private val llmConfig: NamedLlmConfig = NamedLlmConfig(),
    private val providerApiOverride: ((LlmProvider) -> LLMChatAPI?)? = null,
) {
    private val modelFailures = ModelFailureMemory()

    internal suspend fun create(
        key: AgentConversationKey,
        request: BackendConversationTurnRequest,
        initialUsage: LLMResponse.Usage = LLMResponse.Usage(0, 0, 0, 0),
    ): BackendConversationRuntime {
        val persistedSession = agentStateRepository.get(key.userId, key.chatId())?.toConversationSession()
        val settingsProvider = BackendConversationSettingsProvider(
            delegate = baseSettingsProvider,
            defaultSystemPrompt = request.systemPrompt ?: systemPrompt,
            policyPrompt = policyPrompt,
            locale = persistedSession?.locale ?: request.locale,
            useFewShotExamples = request.useFewShotExamples ?: baseSettingsProvider.useFewShotExamples,
            requestTimeoutMillis = request.requestTimeoutMillis ?: baseSettingsProvider.requestTimeoutMillis,
        )
        val temperature = persistedSession?.temperature ?: settingsProvider.temperature
        settingsProvider.restore(
            temperature = temperature,
            locale = persistedSession?.locale ?: request.locale,
        )
        settingsProvider.applyRequest(request = request, temperature = temperature)
        val basedOnMessageSeq = persistedSession?.basedOnMessageSeq ?: 0L
        val initialMessages = loadInitialConversationMessages(
            messageRepository = messageRepository,
            key = key,
            basedOnMessageSeq = basedOnMessageSeq,
            inputMessageSeq = request.inputMessageSeq,
        )

        val hookBudget = request.executionId?.let { id -> hookStore.find(key.userId, UUID.fromString(id)) }
            ?.let { HookLlmBudget(it, hookStore, executionQuotas) }
        val executionApi = BackendExecutionLlmChatApi(
            llmConfig = llmConfig,
            userId = key.userId,
            settingsProvider = settingsProvider,
            credentialResolver = credentialResolver,
            retryPolicy = retryPolicy,
            httpClients = providerHttpClients,
            codexOAuthService = codexOAuthService,
            initialUsage = initialUsage,
            providerApiOverride = providerApiOverride,
            hookBudget = hookBudget,
            metrics = metrics,
            fallbackModels = effectiveSettingsResolver.fallbackModels(key.userId, Locale.forLanguageTag(request.locale)),
            failureMemory = modelFailures,
        )
        val visionGateway = LLMCapabilityResolver(
            settingsProvider = settingsProvider,
            openAiGateway = OpenAIVisionGateway(settingsProvider, executionApi),
            anthropicGateway = AnthropicVisionGateway(settingsProvider, executionApi),
        )
        val imageGenerationGateway = OpenAIImageGenerationGateway(
            settingsProvider = settingsProvider,
            client = providerHttpClients.openAi,
            apiKeyProvider = { executionApi.credentialFor(LlmProvider.OPENAI) },
        )
        val executionLlmToolCatalog = LlmBackedToolCatalog(
            llmApi = executionApi,
            settingsProvider = settingsProvider,
            filesToolUtil = filesToolUtil,
            webResearchClient = webResearchClient,
            visionGateway = visionGateway,
            imageGenerationGateway = ImageGenerationGateway { input ->
                withProviderCall(hookBudget, LlmProvider.OPENAI) { imageGenerationGateway.generate(input) }
            },
        )
        val clientTools = chatRepository.clientTools(key.userId, key.chatId()).filter { it.name != PersonalityTool.NAME }
        val serverNames = toolCatalog.toolsByCategory.values.flatMap { it.keys }.toSet() +
            BackendToolCapabilityPolicy.executionBoundToolNames
        lateinit var baseCatalog: AgentToolCatalog
        lateinit var executionToolCatalog: AgentToolCatalog
        fun withDiscovered(discovered: DiscoveredClientTools?): AgentToolCatalog {
            if (discovered == null) return baseCatalog
            val tools = discovered.tools.filter { it.name != PersonalityTool.NAME }
            val names = tools.map { it.name }.toSet()
            return AgentToolCatalog.compose(
                baseCatalog.filter { _, tool -> tool.fn.name !in names },
                clientSkills.catalog(tools, boundChannelId = discovered.channelId),
            )
        }
        val clientToolCatalog = clientSkills.catalog(clientTools, onDiscovery = { result, channelId ->
            val replacement = try {
                val definitions = clientFrameMapper.treeToValue(result, ClientToolDiscoveryResult::class.java).tools
                    .normalizeClientTools(serverNames + CLIENT_TOOL_DISCOVERY_NAME)
                val discovered = DiscoveredClientTools(channelId, definitions)
                discovered to withDiscovered(discovered)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@catalog ClientError("invalid_client_tool_catalogue", "Orion returned an invalid catalogue; the previous tools remain available.")
            }
            chatRepository.replaceDiscoveredClientTools(key.userId, key.chatId(), replacement.first)
            executionToolCatalog = replacement.second
            null
        })
        baseCatalog = backendExecutionToolCatalog(
            compiledToolCatalog = toolCatalog,
            executionLlmToolCatalog = AgentToolCatalog.compose(executionLlmToolCatalog.catalog,
                AgentToolCatalog(mapOf(ToolCategory.CONFIG to listOf(
                    PersonalityTool(userSettingsRepository, personalitySync, executionApi, settingsProvider, agentBackgroundScope),
                ))),
            ),
            enabledCompiledToolNames = request.enabledTools,
            clientToolCatalog = clientToolCatalog,
            includeFewShotExamples = settingsProvider.useFewShotExamples,
            metrics = metrics,
        )
        executionToolCatalog = withDiscovered(chatRepository.discoveredClientTools(key.userId, key.chatId()))
        val toolTelemetry = metrics?.toolTelemetry(executionToolCatalog.toolsByCategory.values.flatMap { it.keys }.toSet())
            ?: AgentTelemetry.NONE
        val requestToolsFilter = RuntimePassThroughToolsFilter
        val getSkillByNameTool = ToolGetSkillByName(
            catalogProvider = { executionToolCatalog },
            toolsFilter = requestToolsFilter,
            skillBundleProvider = skillBundleProvider,
            approvalGate = null,
        )
        val getSkillsNamesByCategoryTool = ToolGetSkillsNamesByCategory(
            catalogProvider = { executionToolCatalog },
            toolsFilter = requestToolsFilter,
        )
        val getSkillsByCategoryTool = ToolGetSkillsByCategory(
            getSkillByName = getSkillByNameTool,
            getSkillsNamesByCategory = getSkillsNamesByCategoryTool,
        )
        val runtimeCommandTool = ToolInvokeSkill(
            catalogProvider = { executionToolCatalog },
            toolsFilter = requestToolsFilter,
            loadBundle = skillBundleProvider::loadSkillBundle,
            commandExecutor = commandExecutor,
            approvalGate = null,
        )
        val subagentTools = SubagentToolFactory(
            createAgent = { maxTurns ->
                ToolLoopGraphBasedAgent(
                    executionApi,
                    settingsProvider,
                    maxTurns = maxTurns,
                    logObjectMapper = logObjectMapper,
                    telemetry = toolTelemetry,
                )
            },
            catalogProvider = { executionToolCatalog.filter { _, tool -> tool.fn.name != CLIENT_TOOL_DISCOVERY_NAME } },
            toolsFilter = requestToolsFilter,
            skillBundleProvider = skillBundleProvider,
            commandExecutor = commandExecutor,
            configuredModels = settingsProvider.subagentModels,
            commandToolNamesProvider = { catalog -> catalog.toolsByCategory.values.flatMap { it.keys }.toSet() - serverNames },
            commandInstructions = CLIENT_TOOL_ROUTING_INSTRUCTIONS,
        )
        val kernel = AgentExecutionKernelFactory(
            logObjectMapper = logObjectMapper,
            settingsProvider = settingsProvider,
            toolCatalog = executionToolCatalog,
            toolsFilter = requestToolsFilter,
            skillBundleProvider = skillBundleProvider,
            runtimeEnvironment = BackendRequestRuntimeEnvironment(
                localeTag = request.locale,
                timeZone = request.timeZone,
            ),
            coreTools = AgentCoreTools(
                getSkillByName = getSkillByNameTool,
                getSkillsByCategory = getSkillsByCategoryTool,
                getSkillsNamesByCategory = getSkillsNamesByCategoryTool,
                getKnowledge = getKnowledgeTool,
                searchKnowledge = searchKnowledgeTool,
                searchMemory = searchMemoryTool,
                runtimeCommand = runtimeCommandTool,
                subagentTools = { subagentTools.create(it).tools },
                forgetMemory = forgetMemoryTools,
            ),
            knowledgeStore = knowledgeStore,
            telemetry = toolTelemetry,
            errorMessages = BackendAgentErrorMessages,
            llmApi = executionApi,
            conversationInstructions = {
                userSettingsRepository.personalityInstructions(key.userId,
                    canUpdate = PersonalityTool.NAME in baseCatalog.toolsByCategory[ToolCategory.CONFIG].orEmpty())
            },
            skillClassifier = skillClassifier,
            fallbackSkillClassifier = fallbackSkillClassifier,
            complexityClassifier = complexityClassifier,
            memoryRuntime = memoryRuntime,
            automaticMemoryRecall = automaticMemoryRecall,
            captureScope = agentBackgroundScope + backendLogContext(
                "userId" to key.userId,
                "chatId" to key.conversationId,
                "threadId" to request.executionId,
            ),
        ).create()
        return BackendConversationRuntime(
            key = key,
            settingsProvider = settingsProvider,
            contextFactory = kernel.contextFactory,
            executor = kernel.executor,
            executionApi = executionApi,
            persistedSession = persistedSession,
            initialMessages = initialMessages.messages,
            initialObservedMessageSeq = initialMessages.observedThroughSeq,
        )
    }
}
