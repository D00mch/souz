package ru.souz.agent

import kotlinx.coroutines.flow.Flow
import ru.souz.agent.state.AgentContext

class AgentExecutor internal constructor(
    private val agentProvider: (AgentId) -> TraceableAgent,
    val availableAgents: List<AgentId> = listOf(AgentId.GRAPH),
) {
    fun sideEffects(agentId: AgentId): Flow<String> = agentById(agentId).sideEffects

    fun cancelActiveJob(agentId: AgentId) {
        agentById(agentId).cancelActiveJob()
    }

    suspend fun execute(
        agentId: AgentId,
        context: AgentContext<String>,
        input: String,
    ): AgentExecutionResult = executeWithTrace(
        agentId = agentId,
        context = context,
        input = input,
        onStep = null,
    )

    internal suspend fun executeWithTrace(
        agentId: AgentId,
        context: AgentContext<String>,
        input: String,
        onStep: GraphStepCallback?,
    ): AgentExecutionResult {
        val seed = context.copy(input = input)
        return agentById(agentId).executeWithTrace(seed, onStep)
    }

    private fun agentById(agentId: AgentId): TraceableAgent = agentProvider(normalizeAgentId(agentId))

    private fun normalizeAgentId(agentId: AgentId): AgentId =
        if (agentId in availableAgents) agentId else AgentId.default
}
