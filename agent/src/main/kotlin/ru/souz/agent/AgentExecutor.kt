package ru.souz.agent

import kotlinx.coroutines.flow.Flow
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.agent.state.AgentContext

class AgentExecutor internal constructor(
    private val agentProvider: (AgentId) -> TraceableAgent,
    // Execution can be called with an agent ID persisted by a different host configuration.
    // Keep the supported IDs here so provider lookup falls back instead of requesting an unavailable agent.
    private val availableAgents: List<AgentId> = listOf(AgentId.GRAPH, AgentId.SKILLS_GRAPH),
) {
    fun sideEffects(agentId: AgentId): Flow<AgentStreamChunk> = agentById(agentId).sideEffects

    suspend fun cancelActiveJob(agentId: AgentId) {
        agentById(agentId).cancelActiveJob()
    }

    /** Returns true only when the selected agent accepts input into its current open execution. */
    suspend fun submitToActiveRun(agentId: AgentId, input: String): Boolean =
        agentById(agentId).submitToActiveRun(input)

    suspend fun execute(
        agentId: AgentId,
        context: AgentContext<String>,
        input: String,
        eventSink: AgentRuntimeEventSink? = null,
    ): AgentExecutionResult = executeWithTrace(
        agentId = agentId,
        context = context,
        input = input,
        eventSink = eventSink,
        onStep = null,
    )

    internal suspend fun executeWithTrace(
        agentId: AgentId,
        context: AgentContext<String>,
        input: String,
        eventSink: AgentRuntimeEventSink? = null,
        onStep: GraphStepCallback?,
    ): AgentExecutionResult {
        val runtimeEventSink = eventSink ?: context.runtimeEventSink
        val seed = context.copy(
            input = input,
            runtimeEventSink = runtimeEventSink,
        )
        return agentById(agentId).executeWithTrace(seed, onStep)
    }

    private fun agentById(agentId: AgentId): TraceableAgent = agentProvider(normalizeAgentId(agentId))

    private fun normalizeAgentId(agentId: AgentId): AgentId =
        if (agentId in availableAgents) agentId else AgentId.default
}
