package ru.souz.backend

import ru.souz.agent.knowledge.ConversationKnowledgeStore
import ru.souz.agent.knowledge.KnowledgeEntry
import ru.souz.agent.knowledge.KnowledgeWriteResult
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.backend.agent.runtime.BackendSkillCoreToolsFactory
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.tool.skills.ToolRunSkillCommand

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

internal fun testSkillCoreToolsFactory(
    skillRegistryRepository: SkillRegistryRepository = TestSkillRegistryRepository,
): BackendSkillCoreToolsFactory = BackendSkillCoreToolsFactory(
    skillRegistryRepository = skillRegistryRepository,
    legacyCommandTool = testCoreTool("RunSkillCommand"),
    getKnowledgeTool = testCoreTool("GetKnowledge"),
    searchKnowledgeTool = testCoreTool("SearchKnowledge"),
    commandTool = ToolRunSkillCommand(
        ToolInvocationRuntimeSandboxResolver {
            error("The test skill command sandbox is not configured.")
        }
    ),
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
