package ru.souz.backend.agent.runtime.conversation

import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.client.HttpClient
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import ru.souz.agent.knowledge.ConversationKnowledgeStore
import ru.souz.agent.knowledge.KnowledgeEntry
import ru.souz.agent.knowledge.KnowledgeWriteResult
import ru.souz.agent.skills.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.registry.SkillBundleProvider
import ru.souz.agent.skills.registry.StoredSkill
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.backend.agent.runtime.BackendNoopAgentToolCatalog
import ru.souz.backend.agent.session.AgentSessionRepository
import ru.souz.backend.app.BackendProviderRetryPolicy
import ru.souz.backend.chat.repository.MessageRepository
import ru.souz.backend.llm.CredentialSource
import ru.souz.backend.llm.ProviderCredentialResolver
import ru.souz.backend.llm.ResolvedProviderCredential
import ru.souz.backend.testutil.repository.MemoryMessageRepository
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.LlmProvider
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.codex.CodexOAuthService
import ru.souz.llms.http.ProviderHttpClients
import ru.souz.llms.local.LocalChatAPI
import ru.souz.runtime.files.FilesToolUtil
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.tool.skills.SkillCommandExecutor
import ru.souz.tool.web.internal.WebResearchClient

internal fun testBackendConversationRuntimeFactory(
    baseSettingsProvider: SettingsProvider,
    llmApiFactory: suspend (SettingsProvider) -> LLMChatAPI,
    sessionRepository: AgentSessionRepository,
    messageRepository: MessageRepository = MemoryMessageRepository(),
    logObjectMapper: ObjectMapper,
    systemPrompt: String,
    toolCatalog: AgentToolCatalog = BackendNoopAgentToolCatalog,
    skillBundleProvider: SkillBundleProvider = EmptyTestSkillBundleProvider,
    clientToolCatalog: AgentToolCatalog = BackendNoopAgentToolCatalog,
    commandExecutor: SkillCommandExecutor = SkillCommandExecutor(
        sandboxResolver = ToolInvocationRuntimeSandboxResolver {
            error("The test runtime sandbox was not configured.")
        }
    ),
    agentBackgroundScope: CoroutineScope,
): BackendConversationRuntimeFactory = BackendConversationRuntimeFactory(
    baseSettingsProvider = baseSettingsProvider,
    credentialResolver = TestProviderCredentialResolver,
    retryPolicy = BackendProviderRetryPolicy(max429Retries = 0),
    providerHttpClients = TestProviderHttpClients,
    localChatApi = TestLocalChatApi,
    codexOAuthService = CodexOAuthService(baseSettingsProvider, TestProviderHttpClients.standard),
    sessionRepository = sessionRepository,
    messageRepository = messageRepository,
    logObjectMapper = logObjectMapper,
    systemPrompt = systemPrompt,
    toolCatalog = toolCatalog,
    clientToolCatalog = clientToolCatalog,
    skillBundleProvider = skillBundleProvider,
    commandExecutor = commandExecutor,
    filesToolUtil = FilesToolUtil(
        ToolInvocationRuntimeSandboxResolver {
            error("The test runtime sandbox was not configured.")
        }
    ),
    webResearchClient = WebResearchClient(),
    getKnowledgeTool = testCoreTool("GetKnowledge"),
    searchKnowledgeTool = testCoreTool("SearchKnowledge"),
    searchMemoryTool = testCoreTool("SearchMemory"),
    knowledgeStore = EmptyTestKnowledgeStore,
    agentBackgroundScope = agentBackgroundScope,
    testLlmApiFactory = llmApiFactory,
)

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

private fun testCoreTool(name: String): LLMToolSetup = object : LLMToolSetup {
    override val fn: LLMRequest.Function = LLMRequest.Function(
        name = name,
        description = "Test core tool $name.",
        parameters = LLMRequest.Parameters(type = "object"),
    )

    override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
        LLMRequest.Message(
            role = LLMMessageRole.function,
            content = "{}",
            name = functionCall.name,
        )
}

private object EmptyTestSkillBundleProvider : SkillBundleProvider {
    override suspend fun listSkills(userId: String): List<StoredSkill> = emptyList()

    override suspend fun loadSkillBundle(userId: String, skillId: SkillId): SkillBundle? = null
}

private object EmptyTestKnowledgeStore : ConversationKnowledgeStore {
    override suspend fun put(
        meta: ToolInvocationMeta,
        sourceTool: String,
        content: String,
    ): KnowledgeWriteResult = KnowledgeWriteResult.ConversationUnavailable

    override suspend fun get(meta: ToolInvocationMeta, knowledgeId: String): KnowledgeEntry? = null

    override suspend fun clearConversation(meta: ToolInvocationMeta) = Unit
}
