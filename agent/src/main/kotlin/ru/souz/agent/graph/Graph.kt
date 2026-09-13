package ru.souz.agent.graph

import ru.souz.agent.state.AgentContext
import ru.souz.graph.Graph as CoreGraph
import ru.souz.graph.GraphBuilder as CoreGraphBuilder
import ru.souz.graph.Node as CoreNode
import ru.souz.llms.LLMException
import kotlin.properties.ReadOnlyProperty

internal typealias Graph<IN, OUT> = CoreGraph<AgentContext<IN>, AgentContext<OUT>>
internal typealias Node<IN, OUT> = CoreNode<AgentContext<IN>, AgentContext<OUT>>
internal typealias GraphBuilder<IN, OUT> = CoreGraphBuilder<AgentContext<IN>, AgentContext<OUT>>
internal typealias GraphRuntime = ru.souz.graph.GraphRuntime
internal typealias RetryPolicy = ru.souz.graph.RetryPolicy
internal typealias StepInfo = ru.souz.graph.StepInfo

private val defaultRetryPolicy = RetryPolicy(
    maxAttempts = 2,
    shouldRetry = { error, _, node, _ -> error is LLMException && node is RetryableNode<*, *> },
)

/** Opt in only for provider operations whose retry cannot replay tools or surrounding coordination. */
internal fun <IN, OUT> Node(
    name: String,
    retryable: Boolean = false,
    op: suspend (AgentContext<IN>) -> AgentContext<OUT>,
): Node<IN, OUT> {
    val node = ru.souz.graph.Node(name, op)
    return if (retryable) RetryableNode(node) else node
}

private class RetryableNode<IN, OUT>(node: Node<IN, OUT>) : Node<IN, OUT> by node

internal fun <I, O> buildGraph(
    name: String = "Graph",
    retryPolicy: RetryPolicy = defaultRetryPolicy,
    configure: GraphBuilder<I, O>.() -> Unit,
): Graph<I, O> = ru.souz.graph.buildGraph(name, retryPolicy, configure)

internal fun <I, O> graph(
    name: String? = null,
    retryPolicy: RetryPolicy = defaultRetryPolicy,
    configure: GraphBuilder<I, O>.() -> Unit,
): ReadOnlyProperty<Any?, Graph<I, O>> = ru.souz.graph.graph(name, retryPolicy, configure)
