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

    fun start(
        scope: CoroutineScope,
        seed: AgentContext<I>,
        maxSteps: Int = defaultMaxSteps,
        onStep: ((depth: Int, node: Node<Any?, Any?>, ctx: AgentContext<Any?>) -> Unit)? = null,
    ): GraphRun {
        val updates = MutableSharedFlow<AgentContext<*>>(replay = 1)
        updates.tryEmit(seed as AgentContext<*>)
        val job = scope.async {
            try {
                val runtime = GraphRuntime(
                    retryPolicy = retryPolicy,
                    maxSteps = maxSteps,
                    onStep = onStep
                )
                val result = execute(seed, runtime)
                updates.tryEmit(result)
                val finalCtx = result as AgentContext<*>
                updates.tryEmit(finalCtx)
                result
            } catch (cancel: GraphCancellation) {
                updates.tryEmit(cancel.lastContext)
                cancel.lastContext
            }
        }
        return GraphRunImpl(job, updates)
    }
}

interface GraphRun {
    val updates: Flow<AgentContext<*>>
    fun stop(cause: CancellationException? = null)
    suspend fun await(): AgentContext<*>
}

private class GraphRunImpl(
    private val deferred: Deferred<AgentContext<*>>,
    updatesFlow: MutableSharedFlow<AgentContext<*>>,
) : GraphRun {
    override val updates: Flow<AgentContext<*>> = updatesFlow

    override fun stop(cause: CancellationException?) {
        deferred.cancel(cause)
    }

    override suspend fun await(): AgentContext<*> = deferred.await()
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
