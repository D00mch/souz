package com.dumch.agent

import com.dumch.giga.GigaRequest
import com.dumch.giga.GigaToolSetup
import com.dumch.giga.GigaException
import com.dumch.tool.ToolCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.slf4j.Logger
import org.slf4j.LoggerFactory

// Immutable context threaded through the graph
data class AgentContext<I>(
    val input: I,
    val settings: AgentSettings,
    val history: List<GigaRequest.Message>,
    val activeTools: List<GigaRequest.Function>,
    val systemPrompt: String,
) {
    inline fun <reified O> map(
        settings: AgentSettings = this.settings,
        history: List<GigaRequest.Message> = this.history,
        activeTools: List<GigaRequest.Function> = this.activeTools,
        systemPrompt: String = this.systemPrompt,
        transform: (I) -> O,
    ): AgentContext<O> = AgentContext(input = transform(input), settings, history, activeTools, systemPrompt)
}

data class AgentSettings(
    val model: String,
    val temperature: Float,
    val toolsByCategory: Map<ToolCategory, Map<String, GigaToolSetup>>,
    val tools: Map<String, GigaToolSetup> = toolsByCategory.values
        .flatMap { it.entries }
        .associate { it.key to it.value }
)

data class RetryPolicy(
    val maxAttempts: Int = 2,
    val shouldRetry: suspend (
        error: Throwable,
        ctx: AgentContext<*>,
        node: Node<*, *>?,
        attempt: Int
    ) -> Boolean = { error, _, _, _ -> error is GigaException }
)

class EngineCancellation(
    val lastContext: AgentContext<*>,
    cause: CancellationException? = null
) : CancellationException(cause?.message) {
    init {
        initCause(cause)
    }
}

internal data class EngineRuntime(val retryPolicy: RetryPolicy)

// A node transforms Ctx<I> into Ctx<O>
open class Node<I, O>(
    val name: String,
    private val logger: Logger = LoggerFactory.getLogger("NodeLLM"),
    private val op: suspend (AgentContext<I>) -> AgentContext<O>,
) {

    // Transitions keyed by this node's OUTPUT type O
    private val edges = mutableListOf<Transition<O>>()

    internal open suspend fun execute(ctx: AgentContext<I>, runtime: EngineRuntime): AgentContext<O> = op(ctx)

    /** Strict, type-safe edge: next node must accept O as input. */
    fun <NO> edgeTo(target: Node<O, NO>): Node<O, NO> {
        edges += Transition.Static(target)
        return target
    }

    /** Typed conditional edge. Multiple conditionals are checked in insertion order. */
    fun <NO> edgeToIf(pred: (AgentContext<O>) -> Boolean, target: Node<O, NO>): Node<O, NO> {
        edges += Transition.Conditional(pred, target)
        return target
    }

    /** Dynamic router when branches don’t share a single NO type. */
    fun edgeTo(router: suspend (AgentContext<O>) -> Node<O, *>) {
        edges += Transition.Dynamic(router)
    }

    // Inside Node<I, O>
    internal suspend fun resolveNext(ctx: AgentContext<O>): List<Node<O, *>> {
        val nextNodes = ArrayList<Node<O, *>>()
        for (t in edges) {
            when (t) {
                is Transition.Static -> nextNodes.addOrWarn(t.target)
                is Transition.Dynamic<O> -> nextNodes.addOrWarn(t.router(ctx))
                is Transition.Conditional -> if (t.predicate(ctx)) nextNodes.addOrWarn(t.target)
            }
        }
        return nextNodes
    }

    /** Adds a [node] if it's not already added; otherwise log with waring */
    private fun MutableCollection<Node<O, *>>.addOrWarn(node: Node<O, *>) {
        if (this.contains(node)) {
            logger.warn("Node duplication arise. Current node: $this, edge $node")
        } else {
            this.add(node)
        }
    }

    override fun toString(): String = "Node $name; ${Integer.toHexString(hashCode())}"

    private sealed interface Transition<OUT> {
        class Static<OUT>(val target: Node<OUT, *>) : Transition<OUT>
        class Conditional<OUT>(
            val predicate: (AgentContext<OUT>) -> Boolean,
            val target: Node<OUT, *>
        ) : Transition<OUT>

        class Dynamic<OUT>(val router: suspend (AgentContext<OUT>) -> Node<OUT, *>) : Transition<OUT>
    }

    companion object {
        private var counter = 0
        private fun nextId(): Int = ++counter
    }
}

internal class GraphRunner(
    private val retryPolicy: RetryPolicy,
    private val logger: Logger = LoggerFactory.getLogger(Engine::class.java),
) {

    suspend fun run(
        start: Node<Any?, Any?>,
        seed: AgentContext<Any?>,
        maxSteps: Int,
        onStep: ((depth: Int, node: Node<Any?, Any?>, ctx: AgentContext<Any?>) -> Unit)? = null,
        stopPredicate: ((Node<Any?, Any?>, AgentContext<Any?>) -> Boolean)? = null,
    ): AgentContext<Any?> {
        val q = ArrayDeque<Frame>().apply { add(Frame(start, seed, 0)) }
        val leaves = mutableListOf<AgentContext<*>>()
        var processed = 0
        var lastCtx: AgentContext<Any?> = seed

        try {
            while (q.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                if (processed >= maxSteps) error("Engine maxSteps ($maxSteps) reached — potential loop")

                val frame = q.removeFirst()
                val outCtx = executeWithRetry(frame.node, frame.ctx, frame.depth)
                onStep?.invoke(frame.depth, frame.node, outCtx)
                lastCtx = outCtx

                if (stopPredicate?.invoke(frame.node, outCtx) == true) return outCtx

                val nexts = frame.node.resolveNext(outCtx)
                if (nexts.isEmpty()) {
                    leaves += outCtx
                } else {
                    for (child in nexts) {
                        @Suppress("UNCHECKED_CAST")
                        q.add(Frame(child as Node<Any?, Any?>, outCtx, frame.depth + 1))
                    }
                }
                processed++
            }
        } catch (cancel: CancellationException) {
            throw EngineCancellation(lastCtx, cancel)
        }

        @Suppress("UNCHECKED_CAST")
        return leaves.lastOrNull() as? AgentContext<Any?> ?: lastCtx
    }

    private suspend fun executeWithRetry(
        node: Node<Any?, Any?>,
        inCtx: AgentContext<Any?>,
        depth: Int,
    ): AgentContext<Any?> {
        var attempt = 0
        var lastError: Throwable? = null
        val runtime = EngineRuntime(retryPolicy)
        while (attempt < retryPolicy.maxAttempts) {
            attempt++
            try {
                @Suppress("UNCHECKED_CAST")
                return node.execute(inCtx, runtime) as AgentContext<Any?>
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                lastError = t
                val shouldRetry = retryPolicy.shouldRetry(t, inCtx, node, attempt)
                val attemptsLeft = retryPolicy.maxAttempts - attempt
                if (!shouldRetry || attemptsLeft <= 0) break
                logger.warn(
                    "Node '{}' failed on attempt {} (depth {}), retrying ({} attempts left): {}",
                    node.name,
                    attempt,
                    depth,
                    attemptsLeft,
                    t.message,
                    t
                )
            }
        }
        throw lastError ?: IllegalStateException("Unknown failure in node ${node.name}")
    }

    private data class Frame(
        val node: Node<Any?, Any?>,
        val ctx: AgentContext<Any?>,
        val depth: Int,
    )
}

// Graph runner: threads types at compile-time for the start node,
// then uses safe casts between steps (sound if edges were wired correctly).
class Engine(
    private val start: Node<*, *>,
    retryPolicy: RetryPolicy = RetryPolicy(),
) {
    private val runner = GraphRunner(retryPolicy)

    @Suppress("UNCHECKED_CAST")
    suspend fun run(
        seed: AgentContext<*>,
        maxSteps: Int = 1000,
        onStep: ((step: Int, nodeName: String, ctx: AgentContext<*>) -> Unit)? = null
    ): AgentContext<*> {
        val startNode = start as Node<Any?, Any?>
        val startCtx = seed as AgentContext<Any?>
        return runner.run(
            start = startNode,
            seed = startCtx,
            maxSteps = maxSteps,
            onStep = onStep?.let { callback ->
                { depth, node, ctx -> callback(depth, node.name, ctx) }
            }
        )
    }

    fun start(
        scope: CoroutineScope,
        seed: AgentContext<*>,
        maxSteps: Int = 1000,
        onStep: ((step: Int, nodeName: String, ctx: AgentContext<*>) -> Unit)? = null
    ): EngineHandle {
        @Suppress("UNCHECKED_CAST")
        val initial = seed as AgentContext<Any?>
        val state = MutableStateFlow<AgentContext<*>>(initial)
        val job = scope.async {
            try {
                val result = run(initial, maxSteps) { step, node, ctx ->
                    onStep?.invoke(step, node, ctx)
                    state.value = ctx
                }
                state.value = result
                result
            } catch (cancel: EngineCancellation) {
                state.value = cancel.lastContext
                cancel.lastContext
            }
        }
        return EngineHandleImpl(job, state)
    }
}

interface EngineHandle {
    val updates: StateFlow<AgentContext<*>>
    fun stop(cause: CancellationException? = null)
    suspend fun await(): AgentContext<*>
}

private class EngineHandleImpl(
    private val deferred: Deferred<AgentContext<*>>,
    private val state: MutableStateFlow<AgentContext<*>>,
) : EngineHandle {
    override val updates: StateFlow<AgentContext<*>> = state

    override fun stop(cause: CancellationException?) {
        deferred.cancel(cause)
    }

    override suspend fun await(): AgentContext<*> = deferred.await()
}

/*

                                            Test realm below

 */

private suspend fun callLlmMock(ctx: AgentContext<String>): AgentContext<String> {
    val text = ctx.input.trim()
    if (text == "What is the weather today?") return ctx.map { "tool" }
    return ctx.map { "Response: $it" }
}

private suspend fun callToolMock(ctx: AgentContext<String>): AgentContext<String> {
    val tool = "echo"
    return ctx.map { "The weather is fine!" }
}

suspend fun main() {
    val userInputNode = Node<String, String>("userInput") { it }
    val llmCallNode = Node<String, String>("llmCall") { callLlmMock(it) }
    val llmToolUseNode = Node<String, String>("llmToolUse") { callToolMock(it) }
    val userOutputNode = Node<String, String>("userOutput") {
        println("Result it: ${it.input}")
        it
    }

    userInputNode.edgeTo(llmCallNode)
    llmCallNode.edgeTo { ctx ->
        if (ctx.input == "tool") llmToolUseNode else userOutputNode
    }
    llmToolUseNode.edgeTo(llmCallNode)
    // userOutputNode.edgeTo(userInputNode)

    val settings = AgentSettings("gpt5", 0.7f, emptyMap())
    Engine(userInputNode).run(
        seed = AgentContext(input = "What is the weather today?", settings, emptyList(), emptyList(), ""),
        maxSteps = 10
    )
}
