package ru.souz.agent

import ru.souz.agent.graph.StepInfo
import ru.souz.agent.state.AgentContext
import ru.souz.graph.Node

internal typealias GraphStepCallback =
    (step: StepInfo, node: Node<Any?, Any?>, from: AgentContext<Any?>, to: AgentContext<Any?>) -> Unit

internal interface TraceableAgent : Agent {
    suspend fun executeWithTrace(
        ctx: AgentContext<String>,
        onStep: GraphStepCallback? = null,
    ): AgentExecutionResult
}
