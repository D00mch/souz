package ru.souz.graph

import org.slf4j.LoggerFactory
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

class Graph<IN, OUT> internal constructor(
    label: String,
    private val enter: Node<IN, *>,
    private val exit: Node<OUT, OUT>,
    private val retryPolicy: RetryPolicy,
    private val definition: GraphDefinition,
) : Node<IN, OUT> {

    private val runner = GraphRunner()

    override val name: String = "$label::graph"

    @Suppress("UNCHECKED_CAST")
    override suspend fun execute(ctx: IN, runtime: GraphRuntime): OUT {
        return runtime.withGraphScope(name) {
            val result = runner.run(
                start = enter as Node<Any?, Any?>,
                seed = ctx as Any?,
                runtime = runtime,
                definition = definition,
                stopPredicate = { node, _ -> node === exit }
            )
            result as OUT
        }
    }

    suspend fun start(
        seed: IN,
        maxSteps: Int = 1000,
        onStep: ((step: StepInfo, node: Node<Any?, Any?>, from: Any?, to: Any?) -> Unit)? = null,
    ): OUT {
        val runtime = GraphRuntime(
            retryPolicy = retryPolicy,
            maxSteps = maxSteps,
            onStep = onStep,
        )
        return execute(seed, runtime)
    }
}

class GraphBuilder<IN, OUT> internal constructor(
    private val graphName: String,
    private val retryPolicy: RetryPolicy,
) {
    val nodeInput: Node<IN, IN> = Node("$graphName::enter") { it }
    val nodeFinish: Node<OUT, OUT> = Node("$graphName::exit") { it }

    private val transitions: MutableMap<Node<*, *>, MutableList<Transition<*>>> = mutableMapOf()

    fun <IN, OUT, OUT2> Node<IN, OUT>.edgeTo(target: Node<OUT, OUT2>): Node<OUT, OUT2> {
        registerTransition(this, Transition.Static(target))
        return target
    }

    fun <IN, OUT> Node<IN, OUT>.edgeTo(router: suspend (OUT) -> Node<OUT, *>) {
        registerTransition(this, Transition.Dynamic(router))
    }

    private fun <OUT> registerTransition(from: Node<*, OUT>, transition: Transition<OUT>) {
        val bucket = transitions.getOrPut(from) { mutableListOf() }
        bucket += transition
    }

    internal fun build(): Graph<IN, OUT> = Graph(
        graphName,
        nodeInput,
        nodeFinish,
        retryPolicy,
        GraphDefinition(transitions.mapValues { it.value.toList() }),
    )
}

internal class GraphDefinition(
    private val transitions: Map<Node<*, *>, List<Transition<*>>>,
) {
    private val l = LoggerFactory.getLogger(GraphDefinition::class.java)

    @Suppress("UNCHECKED_CAST")
    suspend fun nextNodes(node: Node<Any?, Any?>, ctx: Any?): List<Node<Any?, *>> {
        val registered = transitions[node] as? List<Transition<Any?>> ?: emptyList()
        if (registered.isEmpty()) return emptyList()

        val next = ArrayList<Node<Any?, *>>(registered.size)
        for (transition in registered) {
            when (transition) {
                is Transition.Static -> next.addOrWarn(transition.target)
                is Transition.Dynamic -> next.addOrWarn(transition.router(ctx))
            }
        }
        return next
    }

    private fun MutableCollection<Node<Any?, *>>.addOrWarn(node: Node<Any?, *>) {
        if (contains(node)) {
            l.warn("Node duplication arise. Current node: $this, edge $node")
        } else {
            add(node)
        }
    }
}

internal sealed interface Transition<OUT> {
    class Static<OUT>(val target: Node<OUT, *>) : Transition<OUT>
    class Dynamic<OUT>(val router: suspend (OUT) -> Node<OUT, *>) : Transition<OUT>
}

private val defaultRetryPolicy = RetryPolicy()

fun <I, O> buildGraph(
    name: String = "Graph",
    retryPolicy: RetryPolicy = defaultRetryPolicy,
    configure: GraphBuilder<I, O>.() -> Unit
): Graph<I, O> {
    val builder = GraphBuilder<I, O>(name, retryPolicy)
    builder.configure()
    return builder.build()
}

fun <I, O> graph(
    name: String? = null,
    retryPolicy: RetryPolicy = defaultRetryPolicy,
    configure: GraphBuilder<I, O>.() -> Unit
): ReadOnlyProperty<Any?, Graph<I, O>> = GraphDelegate(name, retryPolicy, configure)


/**
 * Make sure to avoid using [GraphBuilder.nodeInput] from the outsize scope as an entry point.
 * This graph has its own runtime.
 */
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
