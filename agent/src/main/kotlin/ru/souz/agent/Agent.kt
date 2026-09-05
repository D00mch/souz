package ru.souz.agent

import kotlinx.coroutines.flow.Flow
import ru.souz.agent.graph.StepInfo
import ru.souz.agent.state.AgentContext
import ru.souz.graph.Node
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

internal typealias GraphStepCallback =
    (step: StepInfo, node: Node<Any?, Any?>, from: AgentContext<Any?>, to: AgentContext<Any?>) -> Unit

/** Optional capability for publishing input into an open execution. */
internal interface ActiveRunSteer {
    suspend fun submitToActiveRun(build: suspend () -> ActiveRunInput?): Boolean
}

internal interface Agent {
    val sideEffects: Flow<AgentStreamChunk>

    suspend fun cancelActiveJob()

    suspend fun execute(
        ctx: AgentContext<String>,
        onActiveRunReady: suspend () -> Unit = {},
        onStep: GraphStepCallback? = null,
    ): AgentExecutionResult
}
