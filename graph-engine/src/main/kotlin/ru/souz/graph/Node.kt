package ru.souz.graph

interface Node<IN, OUT> {
    val name: String
    suspend fun execute(ctx: IN, runtime: GraphRuntime): OUT
}

/**
 * Create new [Node] implementation based on [op]
 */
fun <IN, OUT> Node(
    name: String,
    op: suspend (IN) -> OUT,
): Node<IN, OUT> = object : Node<IN, OUT> {
    override val name: String = "Node $name; ${Integer.toHexString(hashCode())}"
    override suspend fun execute(ctx: IN, runtime: GraphRuntime) = op(ctx)
}
