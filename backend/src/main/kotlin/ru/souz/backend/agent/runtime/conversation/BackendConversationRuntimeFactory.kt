package ru.souz.backend.agent.runtime.conversation

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.plus
import kotlinx.coroutines.withTimeoutOrNull
import ru.souz.ToolLoopGraphBasedAgent
import ru.souz.agent.AgentCoreTools
import ru.souz.agent.AgentExecutionKernelFactory
import ru.souz.agent.DEFAULT_ATTACHMENT_HISTORY_LIMIT
import ru.souz.agent.knowledge.ConversationKnowledgeStore
import ru.souz.agent.skills.bundle.SkillBundle
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
import ru.souz.backend.attachments.ToolAttachFileToReply
import ru.souz.backend.app.BackendProviderRetryPolicy
import ru.souz.backend.chat.repository.MessageRepository
import ru.souz.backend.client.BackendClientSkills
import ru.souz.backend.client.CLIENT_TOOL_ROUTING_INSTRUCTIONS
import ru.souz.backend.client.CLIENT_TOOL_DISCOVERY_NAME
import ru.souz.backend.common.BackendToolCapabilityPolicy
import ru.souz.backend.common.backendLogContext
import ru.souz.backend.hooks.HookLlmBudget
import ru.souz.backend.hooks.HookStore
import ru.souz.backend.config.NamedLlmConfig
import ru.souz.backend.llm.BackendExecutionLlmChatApi
import ru.souz.backend.llm.ModelFailureMemory
import ru.souz.backend.llm.ProviderCredentialResolver
import ru.souz.backend.llm.quota.ExecutionQuotaManager
import ru.souz.backend.metrics.BackendMetrics
import ru.souz.backend.personality.personalityInstructions
import ru.souz.backend.personality.PersonalitySync
import ru.souz.backend.personality.PersonalityTool
import ru.souz.backend.plugins.PluginExecutionBinding
import ru.souz.backend.settings.repository.UserSettingsRepository
import ru.souz.backend.settings.service.EffectiveSettingsResolver
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.LlmProvider
import ru.souz.llms.codex.CodexOAuthService
import ru.souz.llms.http.ProviderHttpClients
import ru.souz.mcp.McpToolCatalog
import ru.souz.mcp.McpServerConfig
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
import ru.souz.backend.attachments.AttachmentStorage
import ru.souz.backend.attachments.ImageGeneration
import ru.souz.backend.attachments.ToolGenerateAttachmentImage
import ru.souz.backend.llm.ImageModelRoute

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
    private val attachmentHistoryLimit: Int = DEFAULT_ATTACHMENT_HISTORY_LIMIT,
    private val ambientPresetTurn: AmbientPresetTurn? = null,
    private val imageModel: ImageModelRoute,
    private val visionModel: String?,
    private val imageGeneration: ImageGeneration? = null,
    private val attachmentStorage: AttachmentStorage? = null,
) {
    private val modelFailures = ModelFailureMemory()

    internal suspend fun create(
        key: AgentConversationKey,
        request: BackendConversationTurnRequest,
        initialUsage: LLMResponse.Usage = LLMResponse.Usage(0, 0, 0, 0),
        pluginBinding: PluginExecutionBinding? = null,
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
            attachmentStorage = attachmentStorage,
            visionModel = visionModel,
        )
        val imageGenerationGateway = imageModel.gateway(
            settingsProvider, providerHttpClients, codexOAuthService, executionApi, hookBudget,
        )
        val attachmentImages = imageGeneration?.let { service ->
            ToolGenerateAttachmentImage { meta, prompt, ids ->
                service.generate(meta, prompt, ids, imageModel.identity, imageGenerationGateway)
            }
        }
        val executionLlmToolCatalog = LlmBackedToolCatalog(
            llmApi = executionApi,
            settingsProvider = settingsProvider,
            filesToolUtil = filesToolUtil,
            webResearchClient = webResearchClient,
            visionGateway = executionApi.visionGateway,
            imageGenerationGateway = imageGenerationGateway,
        )
        val mcp = McpToolCatalog(
            owner = key.userId,
            servers = userSettingsRepository.get(key.userId)?.mcp.orEmpty().mapNotNull { (id, server) ->
                server.url?.takeIf { server.enabled }?.let { McpServerConfig(id, server.description.ifBlank { id }, it) }
            },
            httpClient = providerHttpClients.mcp,
            timeoutMillis = request.requestTimeoutMillis ?: settingsProvider.requestTimeoutMillis,
        )
        val pluginCatalog = pluginBinding?.catalog ?: AgentToolCatalog()
        val serverNames = toolCatalog.toolsByCategory.values.flatMap { it.keys }.toSet() +
            BackendToolCapabilityPolicy.executionBoundToolNames + pluginCatalog.toolsByCategory.values.flatMap { it.keys } +
            McpToolCatalog.LOAD_TOOL_NAME
        val baseCatalog = backendExecutionToolCatalog(
            compiledToolCatalog = toolCatalog,
            executionLlmToolCatalog = AgentToolCatalog.compose(executionLlmToolCatalog.catalog,
                AgentToolCatalog(mapOf(ToolCategory.IMAGE_GENERATION to listOfNotNull(attachmentImages))),
                AgentToolCatalog(mapOf(ToolCategory.CONFIG to listOf(
                    PersonalityTool(userSettingsRepository, personalitySync, executionApi, settingsProvider, agentBackgroundScope),
                ))),
            ),
            enabledCompiledToolNames = request.enabledTools,
            clientToolCatalog = AgentToolCatalog(),
            includeFewShotExamples = settingsProvider.useFewShotExamples,
            metrics = metrics,
            pluginToolCatalog = pluginCatalog,
        )
        var executionToolCatalog = baseCatalog
        suspend fun refreshClientCatalogue() {
            executionToolCatalog = AgentToolCatalog.compose(baseCatalog,
                clientSkills.catalog(key.userId, serverNames, ::refreshClientCatalogue))
        }
        refreshClientCatalogue()
        val composed = ComposedToolCatalog()
        fun currentToolCatalog() = composed.get(executionToolCatalog, mcp.catalog)
        val channelInstructions = "Current conversation channelId: ${key.conversationId}."
        val toolTelemetry = metrics?.toolTelemetry(executionToolCatalog.toolsByCategory.values.flatMap { it.keys }.toSet())
            ?: AgentTelemetry.NONE
        val requestToolsFilter = RuntimePassThroughToolsFilter
        val getSkillByNameTool = ToolGetSkillByName(
            catalogProvider = ::currentToolCatalog,
            toolsFilter = requestToolsFilter,
            skillBundleProvider = skillBundleProvider,
            approvalGate = null,
        )
        val getSkillsNamesByCategoryTool = ToolGetSkillsNamesByCategory(
            catalogProvider = ::currentToolCatalog,
            toolsFilter = requestToolsFilter,
        )
        val getSkillsByCategoryTool = ToolGetSkillsByCategory(
            catalogProvider = ::currentToolCatalog,
            toolsFilter = requestToolsFilter,
        )
        val runtimeCommandTool = ToolInvokeSkill(
            catalogProvider = ::currentToolCatalog,
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
                    attachmentHistoryLimit = attachmentHistoryLimit,
                )
            },
            catalogProvider = { currentToolCatalog().filter { _, tool -> tool.fn.name != CLIENT_TOOL_DISCOVERY_NAME } },
            toolsFilter = requestToolsFilter,
            skillBundleProvider = skillBundleProvider,
            commandExecutor = commandExecutor,
            configuredModels = settingsProvider.subagentModels,
            commandToolNamesProvider = { catalog -> catalog.toolsByCategory.values.flatMap { it.keys }.toSet() - serverNames },
            commandInstructions = "$channelInstructions $CLIENT_TOOL_ROUTING_INSTRUCTIONS",
        )
        val kernel = AgentExecutionKernelFactory(
            attachmentHistoryLimit = attachmentHistoryLimit,
            logObjectMapper = logObjectMapper,
            settingsProvider = settingsProvider,
            toolCatalog = executionToolCatalog,
            toolCatalogProvider = ::currentToolCatalog,
            toolServerSource = mcp.takeIf { it.servers.isNotEmpty() },
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
                additionalDiscoveryTools = listOfNotNull(mcp.discoveryTool.takeIf { mcp.servers.isNotEmpty() }),
            ),
            knowledgeStore = knowledgeStore,
            telemetry = toolTelemetry,
            errorMessages = BackendAgentErrorMessages,
            llmApi = executionApi,
            conversationInstructions = {
                channelInstructions + "\n\n" + userSettingsRepository.personalityInstructions(key.userId,
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
        val ambient = ambientPresetTurn?.takeIf { hookBudget == null }
        val attach = baseCatalog.toolsByCategory[ToolCategory.FILES]?.get(ToolAttachFileToReply.NAME)
        return BackendConversationRuntime(
            key = key,
            settingsProvider = settingsProvider,
            contextFactory = kernel.contextFactory,
            executor = kernel.executor,
            executionApi = executionApi,
            persistedSession = persistedSession,
            initialMessages = initialMessages.messages,
            initialObservedMessageSeq = initialMessages.observedThroughSeq,
            renderAmbientPreset = if (ambient != null && attach != null) {
                { prompt, meta ->
                    ambient.tryRender(prompt, meta, { bundle -> ambientCommand(attach, bundle, meta.userId) }) { description ->
                        generateAmbientPhrase(key.userId, prompt, description, executionApi)
                    }
                }
            } else null,
            closeExecutionResources = mcp::close,
        )
    }

    private fun ambientCommand(attach: LLMToolSetup, bundle: SkillBundle, userId: String): ToolInvokeSkill {
        val catalog = AgentToolCatalog(mapOf(ToolCategory.FILES to listOf(attach)))
        return ToolInvokeSkill(
            catalogProvider = { catalog },
            toolsFilter = RuntimePassThroughToolsFilter,
            loadBundle = { owner, id -> bundle.takeIf { owner == userId && id == bundle.skillId } },
            commandExecutor = commandExecutor,
            approvalGate = null,
        )
    }

    private suspend fun generateAmbientPhrase(
        userId: String,
        prompt: String,
        description: String,
        api: BackendExecutionLlmChatApi,
    ): String? {
        val model = AmbientPresetTurn.phraseModel
        if (!effectiveSettingsResolver.isSelectableDefaultModel(userId, model.model)) return null
        val instructions = "Write one short caption for an animated background in the language of the user's request. " +
            "Return only the caption, at most 100 characters, without quotation marks."
        val response = withTimeoutOrNull(10_000L) {
            api.messageOnce(LLMRequest.Chat(
                model = model.model,
                provider = model.provider,
                maxTokens = 64,
                messages = listOf(
                    LLMRequest.Message(LLMMessageRole.system, listOfNotNull(policyPrompt, instructions).joinToString("\n\n")),
                    LLMRequest.Message(LLMMessageRole.user, "Background: $description\nRequest: $prompt"),
                ),
            ))
        }
        val choice = (response as? LLMResponse.Chat.Ok)?.choices?.singleOrNull() ?: return null
        if (choice.finishReason != LLMResponse.FinishReason.stop) return null
        return choice.message.content.trim().trim('"', '«', '»').trim()
    }
}
