package ru.souz.backend.agent.runtime.conversation

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CoroutineScope
import ru.souz.agent.knowledge.ConversationKnowledgeStore
import ru.souz.agent.knowledge.KnowledgeEntry
import ru.souz.agent.knowledge.KnowledgeWriteResult
import ru.souz.agent.skills.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.registry.SkillBundleProvider
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.skills.registry.StoredSkill
import ru.souz.agent.skills.validation.SkillValidationRecord
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.backend.agent.runtime.BackendNoopAgentToolCatalog
import ru.souz.backend.agent.session.AgentSessionRepository
import ru.souz.backend.llm.BackendLlmExecutionContext
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.tool.skills.ToolRunSkillCommand

internal fun testBackendConversationRuntimeFactory(
    baseSettingsProvider: SettingsProvider,
    llmApiFactory: suspend (BackendLlmExecutionContext) -> LLMChatAPI,
    sessionRepository: AgentSessionRepository,
    logObjectMapper: ObjectMapper,
    systemPrompt: String,
    toolCatalog: AgentToolCatalog = BackendNoopAgentToolCatalog,
    skillRegistryRepository: SkillRegistryRepository = EmptyTestSkillRegistryRepository,
    clientToolCatalog: AgentToolCatalog = BackendNoopAgentToolCatalog,
    clientSkillBundleProvider: SkillBundleProvider = skillRegistryRepository,
    commandTool: ToolRunSkillCommand = ToolRunSkillCommand(
        sandboxResolver = ToolInvocationRuntimeSandboxResolver {
            error("The test runtime sandbox was not configured.")
        }
    ),
    agentBackgroundScope: CoroutineScope,
): BackendConversationRuntimeFactory = BackendConversationRuntimeFactory(
    baseSettingsProvider = baseSettingsProvider,
    llmApiFactory = llmApiFactory,
    sessionRepository = sessionRepository,
    logObjectMapper = logObjectMapper,
    systemPrompt = systemPrompt,
    toolCatalog = toolCatalog,
    clientToolCatalog = clientToolCatalog,
    clientSkillBundleProvider = clientSkillBundleProvider,
    skillRegistryRepository = skillRegistryRepository,
    commandTool = commandTool,
    getKnowledgeTool = testCoreTool("GetKnowledge"),
    searchKnowledgeTool = testCoreTool("SearchKnowledge"),
    searchMemoryTool = testCoreTool("SearchMemory"),
    knowledgeStore = EmptyTestKnowledgeStore,
    agentBackgroundScope = agentBackgroundScope,
)

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

private object EmptyTestSkillRegistryRepository : SkillRegistryRepository {
    override suspend fun listSkills(userId: String): List<StoredSkill> = emptyList()

    override suspend fun saveSkillBundle(userId: String, bundle: SkillBundle): StoredSkill =
        error("Saving Skills is not supported by this test repository.")

    override suspend fun loadSkillBundle(userId: String, skillId: SkillId): SkillBundle? = null

    override suspend fun getValidation(
        userId: String,
        skillId: SkillId,
        bundleHash: String,
        policyVersion: String,
    ): SkillValidationRecord? = null

    override suspend fun saveValidation(record: SkillValidationRecord) = Unit
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
