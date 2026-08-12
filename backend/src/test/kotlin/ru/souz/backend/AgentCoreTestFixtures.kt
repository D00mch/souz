package ru.souz.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.HttpClient
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ru.souz.agent.AgentId
import ru.souz.agent.knowledge.ConversationKnowledgeStore
import ru.souz.agent.knowledge.KnowledgeEntry
import ru.souz.agent.knowledge.KnowledgeWriteResult
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.backend.agent.runtime.BackendNoopAgentToolCatalog
import ru.souz.backend.agent.runtime.conversation.BackendConversationRuntimeFactory
import ru.souz.backend.agent.session.AgentSessionRepository
import ru.souz.backend.agent.session.InMemoryAgentSessionRepository
import ru.souz.backend.app.BackendProviderRetryPolicy
import ru.souz.backend.llm.CredentialSource
import ru.souz.backend.llm.ProviderCredentialResolver
import ru.souz.backend.llm.ResolvedProviderCredential
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LlmProvider
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.codex.CodexOAuthService
import ru.souz.llms.http.ProviderHttpClients
import ru.souz.llms.local.LocalChatAPI
import ru.souz.memory.NoopConversationMemoryRuntime
import ru.souz.runtime.files.FilesToolUtil
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.tool.skills.ToolRunSkillCommand
import ru.souz.tool.memory.ToolSearchMemory
import ru.souz.tool.web.internal.WebResearchClient

internal fun testCoreTool(name: String): LLMToolSetup = object : LLMToolSetup {
    override val fn: LLMRequest.Function = LLMRequest.Function(
        name = name,
        description = "backend test core tool",
        parameters = LLMRequest.Parameters(type = "object", properties = emptyMap()),
    )

    override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
        LLMRequest.Message(
            role = LLMMessageRole.function,
            content = "ok",
            name = functionCall.name,
        )
}

internal fun testSkillCommandTool(): ToolRunSkillCommand = ToolRunSkillCommand(
    ToolInvocationRuntimeSandboxResolver {
        error("The test skill command sandbox is not configured.")
    }
)

internal fun testSearchMemoryTool(): LLMToolSetup =
    ToolSearchMemory(NoopConversationMemoryRuntime)

internal fun testBackendConversationRuntimeFactory(
    settingsProvider: SettingsProvider = TestSettingsProvider(),
    llmApiFactory: suspend (SettingsProvider) -> LLMChatAPI,
    sessionRepository: AgentSessionRepository = InMemoryAgentSessionRepository(),
    logObjectMapper: ObjectMapper = jacksonObjectMapper(),
    systemPrompt: String = "backend test prompt",
    configuredAgentId: AgentId = AgentId.GRAPH,
    toolCatalog: AgentToolCatalog = BackendNoopAgentToolCatalog,
    clientToolCatalog: AgentToolCatalog = BackendNoopAgentToolCatalog,
    skillRegistryRepository: SkillRegistryRepository? = null,
    agentBackgroundScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
): BackendConversationRuntimeFactory = BackendConversationRuntimeFactory(
    baseSettingsProvider = settingsProvider,
    credentialResolver = TestProviderCredentialResolver,
    retryPolicy = BackendProviderRetryPolicy(max429Retries = 0),
    providerHttpClients = TestProviderHttpClients,
    localChatApi = TestLocalChatApi,
    codexOAuthService = CodexOAuthService(settingsProvider, TestProviderHttpClients.standard),
    sessionRepository = sessionRepository,
    logObjectMapper = logObjectMapper,
    systemPrompt = systemPrompt,
    configuredAgentId = configuredAgentId,
    toolCatalog = toolCatalog,
    clientToolCatalog = clientToolCatalog,
    skillRegistryRepository = skillRegistryRepository,
    legacyCommandTool = testCoreTool("RunCommand"),
    commandTool = testSkillCommandTool(),
    filesToolUtil = FilesToolUtil(
        ToolInvocationRuntimeSandboxResolver {
            error("The test runtime sandbox is not configured.")
        }
    ),
    webResearchClient = WebResearchClient(),
    getKnowledgeTool = testCoreTool("GetKnowledge"),
    searchKnowledgeTool = testCoreTool("SearchKnowledge"),
    searchMemoryTool = testSearchMemoryTool(),
    knowledgeStore = TestConversationKnowledgeStore,
    agentBackgroundScope = agentBackgroundScope,
    testLlmApiFactory = llmApiFactory,
)

internal object TestConversationKnowledgeStore : ConversationKnowledgeStore {
    override suspend fun put(
        meta: ToolInvocationMeta,
        sourceTool: String,
        content: String,
    ): KnowledgeWriteResult = KnowledgeWriteResult.ConversationUnavailable

    override suspend fun get(meta: ToolInvocationMeta, knowledgeId: String): KnowledgeEntry? = null

    override suspend fun clearConversation(meta: ToolInvocationMeta) = Unit
}

private val TestProviderHttpClient = mockk<HttpClient>(relaxed = true)

private val TestProviderHttpClients = ProviderHttpClients(
    standard = TestProviderHttpClient,
    openAi = TestProviderHttpClient,
)

private val TestLocalChatApi = mockk<LocalChatAPI>(relaxed = true)

private object TestProviderCredentialResolver : ProviderCredentialResolver {
    override suspend fun resolve(userId: String, provider: LlmProvider): ResolvedProviderCredential =
        ResolvedProviderCredential(
            provider = provider,
            apiKey = "test-key",
            source = CredentialSource.SERVER_MANAGED,
        )
}
