package com.dumch.agent.engine

interface Node<IN, OUT> {
    val name: String
    suspend fun execute(ctx: AgentContext<IN>, runtime: GraphRuntime): AgentContext<OUT>
}

/**
 * Create new [Node] implementation based on [op]
 */
fun <IN, OUT> Node(
    name: String,
    op: suspend (AgentContext<IN>) -> AgentContext<OUT>,
): Node<IN, OUT> = object : Node<IN, OUT> {
    override val name: String = "Node $name; ${Integer.toHexString(hashCode())}"
    override suspend fun execute(ctx: AgentContext<IN>, runtime: GraphRuntime) = op(ctx)
}