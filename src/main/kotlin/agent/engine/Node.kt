package com.dumch.agent.engine

import org.slf4j.Logger
import org.slf4j.LoggerFactory

// A node transforms Ctx<I> into Ctx<O>
open class Node<I, O>(
    val name: String,
    private val logger: Logger = LoggerFactory.getLogger("NodeLLM"),
    private val op: suspend (AgentContext<I>) -> AgentContext<O>,
) {

    // Transitions keyed by this node's OUTPUT type O
    private val edges = mutableListOf<Transition<O>>()

    internal open suspend fun execute(ctx: AgentContext<I>, runtime: GraphRuntime): AgentContext<O> = op(ctx)

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