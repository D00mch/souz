package ru.souz.agent

import ru.souz.agent.graph.StepInfo
import ru.souz.agent.state.AgentContext
import ru.souz.graph.Node

internal typealias GraphStepCallback =
    (step: StepInfo, node: Node<Any?, Any?>, from: AgentContext<Any?>, to: AgentContext<Any?>) -> Unit

/** Optional capability for publishing input into an open execution. */
internal interface ActiveRunSteer {
    suspend fun submitToActiveRun(build: suspend () -> ActiveRunInput?): Boolean
}

internal interface TraceableAgent : Agent {
    suspend fun executeWithTrace(
        ctx: AgentContext<String>,
        onActiveRunReady: suspend () -> Unit = {},
        onStep: GraphStepCallback? = null,
    ): AgentExecutionResult
}
