package ru.souz.agent

import kotlinx.coroutines.flow.Flow
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.agent.state.AgentContext

class AgentExecutor internal constructor(
    private val agentProvider: (AgentId) -> Agent,
    // Execution can be called with an agent ID persisted by a different host configuration.
    // Keep the supported IDs here so provider lookup falls back instead of requesting an unavailable agent.
    private val availableAgents: List<AgentId> = listOf(AgentId.GRAPH, AgentId.SKILLS_GRAPH),
) {
    init {
        require(availableAgents.isNotEmpty()) { "At least one agent must be available." }
    }

    fun sideEffects(agentId: AgentId): Flow<AgentStreamChunk> = agentById(agentId).sideEffects

    fun supportsActiveRunInput(agentId: AgentId): Boolean =
        activeRunInterruptor(agentId) != null

    suspend fun cancelActiveJob(agentId: AgentId) {
        agentById(agentId).cancelActiveJob()
    }

    /** Publishes a batch only after the selected agent keeps its run open and [build] succeeds. */
    suspend fun submitToActiveRun(
        agentId: AgentId,
        build: suspend () -> ActiveRunInput?,
    ): Boolean = activeRunInterruptor(agentId)?.submitToActiveRun(build) ?: false

    suspend fun execute(
        agentId: AgentId,
        context: AgentContext<String>,
        input: String,
        eventSink: AgentRuntimeEventSink? = null,
        onActiveRunReady: suspend () -> Unit = {},
    ): AgentExecutionResult = executeWithTrace(
        agentId = agentId,
        context = context,
        input = input,
        eventSink = eventSink,
        onActiveRunReady = onActiveRunReady,
        onStep = null,
    )

    internal suspend fun executeWithTrace(
        agentId: AgentId,
        context: AgentContext<String>,
        input: String,
        eventSink: AgentRuntimeEventSink? = null,
        onActiveRunReady: suspend () -> Unit = {},
        onStep: GraphStepCallback? = null,
    ): AgentExecutionResult {
        val runtimeEventSink = eventSink ?: context.runtimeEventSink
        val seed = context.copy(
            input = input,
            runtimeEventSink = runtimeEventSink,
        )
        return agentById(agentId).execute(seed, onActiveRunReady, onStep)
    }

    private fun agentById(agentId: AgentId): Agent = agentProvider(normalizeAgentId(agentId))

    private fun activeRunInterruptor(agentId: AgentId): ActiveRunSteer? =
        agentById(agentId) as? ActiveRunSteer

    private fun normalizeAgentId(agentId: AgentId): AgentId =
        if (agentId in availableAgents) agentId else availableAgents.first()
}
