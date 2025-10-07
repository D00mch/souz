package com.dumch.agent

import org.slf4j.LoggerFactory
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

fun <I, O> subgraph(
    name: String? = null,
    maxSteps: Int = 100,
    configure: SubgraphBuilder<I, O>.() -> Unit
): ReadOnlyProperty<Any?, Node<I, O>> = SubgraphDelegate(name, maxSteps, configure)

class SubgraphBuilder<I, O> internal constructor(
    private val graphName: String,
    private val maxSteps: Int,
) {
    val input: Node<I, I> = Node("$graphName::input") { it }
    private val finishNode: Node<O, O> = Node("$graphName::finish") { it }
    val NodeFinish: Node<O, O> get() = finishNode

    internal fun build(): Node<I, O> = SubgraphNode(graphName, input, finishNode, maxSteps)
}

private class SubgraphDelegate<I, O>(
    private val nameHint: String?,
    private val maxSteps: Int,
    private val configure: SubgraphBuilder<I, O>.() -> Unit
) : ReadOnlyProperty<Any?, Node<I, O>> {
    private var cached: Node<I, O>? = null

    override fun getValue(thisRef: Any?, property: KProperty<*>): Node<I, O> {
        return cached ?: build(property.name).also { cached = it }
    }

    private fun build(propertyName: String): Node<I, O> {
        val name = nameHint ?: propertyName
        val builder = SubgraphBuilder<I, O>(name, maxSteps)
        builder.configure()
        return builder.build()
    }
}

private class SubgraphNode<I, O>(
    private val label: String,
    private val entry: Node<I, *>,
    private val finish: Node<O, O>,
    private val maxSteps: Int,
) : Node<I, O>("$label::subgraph", LoggerFactory.getLogger("Subgraph:$label"), { error("Subgraph node should not invoke base op") }) {

    @Suppress("UNCHECKED_CAST")
    override suspend fun execute(ctx: AgentContext<I>, runtime: EngineRuntime): AgentContext<O> {
        val runner = GraphRunner(runtime.retryPolicy)
        val result = runner.run(
            start = entry as Node<Any?, Any?>,
            seed = ctx as AgentContext<Any?>,
            maxSteps = maxSteps,
            stopPredicate = { node, _ -> node === finish }
        )
        return result as AgentContext<O>
    }
}
