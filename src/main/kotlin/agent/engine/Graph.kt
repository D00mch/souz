package com.dumch.agent.engine

import org.slf4j.LoggerFactory
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

class Graph<I, O> internal constructor(
    label: String,
    private val entry: Node<I, *>,
    private val finish: Node<O, O>,
    private val retryPolicy: RetryPolicy,
) : Node<I, O>("$label::graph", LoggerFactory.getLogger("Graph:$label"), { error("Graph node should not invoke base op") }) {

    private val runner = GraphRunner()

    @Suppress("UNCHECKED_CAST")
    override suspend fun execute(ctx: AgentContext<I>, runtime: GraphRuntime): AgentContext<O> {
        val result = runner.run(
            start = entry as Node<Any?, Any?>,
            seed = ctx as AgentContext<Any?>,
            runtime = runtime,
            stopPredicate = { node, _ -> node === finish }
        )
        return result as AgentContext<O>
    }

    suspend fun start(
        seed: AgentContext<I>,
        maxSteps: Int = 1000,
        onStep: ((step: StepInfo, node: Node<Any?, Any?>, ctx: AgentContext<Any?>) -> Unit)? = null,
    ): AgentContext<O> {
        val runtime = GraphRuntime(
            retryPolicy = retryPolicy,
            maxSteps = maxSteps,
            onStep = onStep,
        )
        return execute(seed, runtime)
    }
}

class GraphBuilder<I, O> internal constructor(
    private val graphName: String,
    private val retryPolicy: RetryPolicy,
) {
    val nodeInput: Node<I, I> = Node("$graphName::input") { it }
    val nodeFinish: Node<O, O> = Node("$graphName::finish") { it }

    internal fun build(): Graph<I, O> = Graph(graphName, nodeInput, nodeFinish, retryPolicy)
}

fun <I, O> buildGraph(
    name: String = "Graph",
    retryPolicy: RetryPolicy = RetryPolicy(),
    configure: GraphBuilder<I, O>.() -> Unit
): Graph<I, O> {
    val builder = GraphBuilder<I, O>(name, retryPolicy)
    builder.configure()
    return builder.build()
}

fun <I, O> graph(
    name: String? = null,
    retryPolicy: RetryPolicy = RetryPolicy(),
    configure: GraphBuilder<I, O>.() -> Unit
): ReadOnlyProperty<Any?, Graph<I, O>> = GraphDelegate(name, retryPolicy, configure)

private class GraphDelegate<I, O>(
    private val nameHint: String?,
    private val retryPolicy: RetryPolicy,
    private val configure: GraphBuilder<I, O>.() -> Unit,
) : ReadOnlyProperty<Any?, Graph<I, O>> {
    private var cached: Graph<I, O>? = null

    override fun getValue(thisRef: Any?, property: KProperty<*>): Graph<I, O> {
        return cached ?: build(property.name).also { cached = it }
    }

    private fun build(propertyName: String): Graph<I, O> {
        val name = nameHint ?: propertyName
        val builder = GraphBuilder<I, O>(name, retryPolicy)
        builder.configure()
        return builder.build()
    }
}
