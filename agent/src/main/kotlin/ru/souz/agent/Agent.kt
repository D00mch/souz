package ru.souz.agent

import kotlinx.coroutines.flow.Flow
import ru.souz.agent.state.AgentContext
import ru.souz.graph.Node
import ru.souz.graph.StepInfo
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse

sealed interface AgentSideEffect {
    data class Text(
        val v: String,
        val streamRevision: Long = 0L,
    ) : AgentSideEffect

    data class Fn(val call: LLMResponse.FunctionCall) : AgentSideEffect
}

/** Text produced by the LLM branch identified by [streamRevision]. */
data class AgentStreamChunk(
    val text: String,
    val streamRevision: Long,
)

/** Durable messages observed before one user input submitted to an active execution. */
data class ActiveRunInput(
    val history: List<LLMRequest.Message> = emptyList(),
    val input: String,
)

data class AgentExecutionResult(
    val output: String,
    val context: AgentContext<String>,
)

typealias GraphStepCallback =
    (step: StepInfo, node: Node<Any?, Any?>, from: AgentContext<Any?>, to: AgentContext<Any?>) -> Unit

/** Optional capability for publishing input into an open execution. */
internal interface ActiveRunSteer {
    suspend fun submitToActiveRun(build: suspend () -> ActiveRunInput?): Boolean
}

/** One active execution per instance; concurrent callers need separate agents. */
interface Agent {
    val sideEffects: Flow<AgentStreamChunk>

    suspend fun cancelActiveJob()

    suspend fun execute(
        ctx: AgentContext<String>,
        onActiveRunReady: suspend () -> Unit = {},
        onStep: GraphStepCallback? = null,
    ): AgentExecutionResult
}

class AgentTurnLimitException(
    val maxTurns: Int,
    /** Tool results from this execution, including tool-level errors; these do not imply task success. */
    val toolResults: List<LLMRequest.Message> = emptyList(),
) : IllegalStateException(
    "Agent reached its limit of $maxTurns model turns without a final answer. " +
        "Execution is incomplete; tool side effects are not rolled back. Inspect tool results and current state before retrying.",
)
