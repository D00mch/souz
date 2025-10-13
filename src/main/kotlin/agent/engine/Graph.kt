package com.dumch.agent.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.slf4j.LoggerFactory
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

class Graph<I, O> internal constructor(
    label: String,
    private val entry: Node<I, *>,
    private val finish: Node<O, O>,
    private val retryPolicy: RetryPolicy,
    private val defaultMaxSteps: Int,
) : Node<I, O>("$label::graph", LoggerFactory.getLogger("Graph:$label"), { error("Graph node should not invoke base op") }) {

    private val runner = GraphRunner()

    @Suppress("UNCHECKED_CAST")
    override suspend fun execute(ctx: AgentContext<I>, runtime: GraphRuntime): AgentContext<O> {
        val subRuntime = runtime.forSubgraph(defaultMaxSteps)
        val result = runner.run(
            start = entry as Node<Any?, Any?>,
            seed = ctx as AgentContext<Any?>,
            runtime = subRuntime,
            stopPredicate = { node, _ -> node === finish }
        )
        return result as AgentContext<O>
    }

    suspend fun start(
        seed: AgentContext<I>,
        maxSteps: Int = defaultMaxSteps,
        onStep: ((step: StepInfo, node: Node<Any?, Any?>, ctx: AgentContext<Any?>) -> Unit)? = null,
    ): AgentContext<O> {
        val runtime = GraphRuntime(
            retryPolicy = retryPolicy,
            maxSteps = maxSteps,
            onStep = onStep
        )
        return execute(seed, runtime)
    }
}

class GraphBuilder<I, O> internal constructor(
    private val graphName: String,
    private val maxSteps: Int,
    private val retryPolicy: RetryPolicy,
) {
    val input: Node<I, I> = Node("$graphName::input") { it }
    val nodeFinish: Node<O, O> = Node("$graphName::finish") { it }

    internal fun build(): Graph<I, O> = Graph(graphName, input, nodeFinish, retryPolicy, maxSteps)
}

fun <I, O> buildGraph(
    name: String = "Graph",
    maxSteps: Int = 1000,
    retryPolicy: RetryPolicy = RetryPolicy(),
    configure: GraphBuilder<I, O>.() -> Unit
): Graph<I, O> {
    val builder = GraphBuilder<I, O>(name, maxSteps, retryPolicy)
    builder.configure()
    return builder.build()
}

fun <I, O> graph(
    name: String? = null,
    maxSteps: Int = 1000,
    retryPolicy: RetryPolicy = RetryPolicy(),
    configure: GraphBuilder<I, O>.() -> Unit
): ReadOnlyProperty<Any?, Graph<I, O>> = GraphDelegate(name, maxSteps, retryPolicy, configure)

private class GraphDelegate<I, O>(
    private val nameHint: String?,
    private val maxSteps: Int,
    private val retryPolicy: RetryPolicy,
    private val configure: GraphBuilder<I, O>.() -> Unit,
) : ReadOnlyProperty<Any?, Graph<I, O>> {
    private var cached: Graph<I, O>? = null

    override fun getValue(thisRef: Any?, property: KProperty<*>): Graph<I, O> {
        return cached ?: build(property.name).also { cached = it }
    }

    private fun build(propertyName: String): Graph<I, O> {
        val name = nameHint ?: propertyName
        val builder = GraphBuilder<I, O>(name, maxSteps, retryPolicy)
        builder.configure()
        return builder.build()
    }
}
