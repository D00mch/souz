package ru.souz.backend

import java.time.Instant
import ru.souz.agent.knowledge.ConversationKnowledgeStore
import ru.souz.agent.knowledge.KnowledgeEntry
import ru.souz.agent.knowledge.KnowledgeWriteResult
import ru.souz.memory.NoopConversationMemoryRuntime
import ru.souz.backend.client.BackendClientSkills
import ru.souz.backend.client.ClientThreadRuntimeRegistry
import ru.souz.backend.events.bus.AgentEventBus
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.testutil.repository.MemoryAgentEventRepository
import ru.souz.backend.testutil.repository.MemoryChatRepository
import ru.souz.backend.testutil.repository.MemoryToolCallRepository
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.tool.skills.ToolRunSkillCommand
import ru.souz.tool.memory.ToolSearchMemory

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

internal fun testRunSkillCommandTool(): ToolRunSkillCommand =
    ToolRunSkillCommand(
        ToolInvocationRuntimeSandboxResolver {
            error("The test skill command sandbox is not configured.")
        }
    )

internal fun testBackendClientSkills(
    registry: ClientThreadRuntimeRegistry = ClientThreadRuntimeRegistry(),
    now: () -> Instant = Instant::now,
): BackendClientSkills =
    BackendClientSkills(
        registry = registry,
        toolCallRepository = MemoryToolCallRepository(),
        eventService = AgentEventService(
            chatRepository = MemoryChatRepository(),
            eventRepository = MemoryAgentEventRepository(),
            eventBus = AgentEventBus(),
        ),
        now = now,
    )

internal fun testSearchMemoryTool(): LLMToolSetup =
    ToolSearchMemory(NoopConversationMemoryRuntime)

internal object TestConversationKnowledgeStore : ConversationKnowledgeStore {
    override suspend fun put(
        meta: ToolInvocationMeta,
        sourceTool: String,
        content: String,
    ): KnowledgeWriteResult = KnowledgeWriteResult.ConversationUnavailable

    override suspend fun get(meta: ToolInvocationMeta, knowledgeId: String): KnowledgeEntry? = null

    override suspend fun clearConversation(meta: ToolInvocationMeta) = Unit
}
