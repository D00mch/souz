package ru.souz.agent

import kotlinx.coroutines.flow.Flow
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.agent.state.AgentContext
import ru.souz.llms.LLMRequest

class AgentExecutor internal constructor(
    private val agentProvider: (AgentId) -> Agent,
    // Execution can be called with an agent ID persisted by a different host configuration.
    // Keep the supported IDs here so provider lookup falls back instead of requesting an unavailable agent.
    private val availableAgents: List<AgentId> = listOf(AgentId.GRAPH, AgentId.SKILLS_GRAPH),
    private val onStep: GraphStepCallback? = null,
) {
    init {
        require(availableAgents.isNotEmpty()) { "At least one agent must be available." }
    }

    internal fun stream(agentId: AgentId): Flow<AgentStreamChunk> = agentById(agentId).stream

    internal fun cancel(agentId: AgentId) = agentById(agentId).cancel()

    suspend fun execute(
        agentId: AgentId,
        context: AgentContext<String>,
        input: String,
        eventSink: AgentRuntimeEventSink? = null,
        loadPendingHistory: suspend () -> List<LLMRequest.Message> = { emptyList() },
        onActiveRunReady: suspend (ActiveRunMailbox) -> Unit = {},
    ): AgentExecutionResult {
        val runtimeEventSink = eventSink ?: context.runtimeEventSink
        val seed = context.copy(
            input = input,
            runtimeEventSink = runtimeEventSink,
        )
        return agentById(agentId).execute(seed, loadPendingHistory, onActiveRunReady, onStep)
    }

    private fun agentById(agentId: AgentId): Agent = agentProvider(normalizeAgentId(agentId))

    private fun normalizeAgentId(agentId: AgentId): AgentId =
        if (agentId in availableAgents) agentId else availableAgents.first()
}
