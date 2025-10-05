package com.dumch.agent

import com.dumch.giga.GigaRequest
import com.dumch.giga.GigaToolSetup
import com.dumch.tool.ToolCategory
import org.slf4j.Logger
import java.util.UUID
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

// A node transforms Ctx<I> into Ctx<O>
class Node<I, O>(
    val name: String,
    private val logger: Logger = LoggerFactory.getLogger("NodeLLM"),
    private val op: suspend (AgentContext<I>) -> AgentContext<O>,
) {

    // Transitions keyed by this node's OUTPUT type O
    private val edges = mutableListOf<Transition<O>>()

    suspend fun execute(ctx: AgentContext<I>): AgentContext<O> = op(ctx)

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

// Graph runner: threads types at compile-time for the start node,
// then uses safe casts between steps (sound if edges were wired correctly).
class Engine(
    private val start: Node<*, *>,
) {

    @Suppress("UNCHECKED_CAST")
    suspend fun run(
        seed: AgentContext<*>,
        maxSteps: Int = 1000,
        onStep: ((step: Int, nodeName: String, ctx: AgentContext<*>) -> Unit)? = null
    ): AgentContext<*> {
        val startNode = start as Node<Any?, Any?>
        val startCtx  = seed  as AgentContext<Any?>

        val q = ArrayDeque<Frame>().apply { add(Frame(startNode, startCtx, 0)) }

        val leaves = mutableListOf<AgentContext<*>>() // terminal results
        var processed = 0

        while (q.isNotEmpty()) {
            if (processed >= maxSteps) error("Engine maxSteps ($maxSteps) reached — potential loop")

            val (node, inCtx, depth) = q.removeFirst()
            onStep?.invoke(depth, node.name, inCtx)

            val outCtx = node.execute(inCtx)
            val nexts  = node.resolveNext(outCtx)

            if (nexts.isEmpty()) {
                leaves += outCtx
            } else {
                for (child in nexts) {
                    q.add(Frame(child as Node<Any?, Any?>, outCtx, depth + 1))
                }
            }
            processed++
        }

        // Keep the old signature: return the last leaf (BFS order).
        return leaves.lastOrNull() ?: startCtx
    }

    private data class Frame(
        val node: Node<Any?, Any?>,
        val ctx: AgentContext<Any?>,
        val depth: Int
    )
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
