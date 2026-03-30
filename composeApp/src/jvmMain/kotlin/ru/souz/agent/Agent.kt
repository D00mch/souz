package ru.souz.agent

import kotlinx.coroutines.flow.Flow
import ru.souz.agent.engine.AgentContext
import ru.souz.agent.engine.Graph
import ru.souz.agent.engine.Node
import ru.souz.agent.engine.StepInfo
import ru.souz.llms.GigaResponse

sealed interface AgentSideEffect {
    @JvmInline
    value class Text(val v: String) : AgentSideEffect

    data class Fn(val call: GigaResponse.FunctionCall) : AgentSideEffect
}

interface Agent {
    val sideEffects: Flow<String>
    val graph: Graph<String, String>
    suspend fun execute(ctx: AgentContext<String>): String
    fun cancelActiveJob()
}

data class AgentExecutionResult(
    val output: String,
    val context: AgentContext<String>,
)

internal typealias GraphStepCallback =
    (step: StepInfo, node: Node<Any?, Any?>, from: AgentContext<Any?>, to: AgentContext<Any?>) -> Unit

internal interface TraceableAgent : Agent {
    suspend fun executeWithTrace(
        ctx: AgentContext<String>,
        onStep: GraphStepCallback? = null,
    ): AgentExecutionResult
}
