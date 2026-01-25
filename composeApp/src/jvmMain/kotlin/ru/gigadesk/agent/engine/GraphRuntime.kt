package ru.gigadesk.agent.engine

import ru.gigadesk.giga.GigaException
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicInteger

class GraphCancellation(
    val lastContext: AgentContext<*>,
    cause: CancellationException? = null
) : CancellationException(cause?.message) {
    init {
        initCause(cause)
    }
}

data class StepInfo(
    /**
     * Sequential index of the executed node within the run (starting from 0).
     */
    val index: Int,
    /**
     * Sequential index of the executed node within the current graph run (starting from 0).
     */
    val currentGraphIndex: Int,
    /**
     * Name of the current graph being executed.
     */
    val graphName: String,
    /**
     * Depth of the current graph in the execution stack (root graph = 0).
     */
    val graphDepth: Int,
    /**
     * True when executing inside a subgraph (non-root graph).
     */
    val isSubgraph: Boolean = graphDepth > 0,
)

class GraphRuntime private constructor(
    val retryPolicy: RetryPolicy,
    val maxSteps: Int,
    val onStep: ((step: StepInfo, node: Node<Any?, Any?>, from: AgentContext<Any?>, to: AgentContext<Any?>) -> Unit)? = null,
    val counter: AtomicInteger,
    val graphStack: ArrayDeque<String>
) {
    constructor(
        retryPolicy: RetryPolicy,
        maxSteps: Int,
        onStep: ((step: StepInfo, node: Node<Any?, Any?>, from: AgentContext<Any?>, to: AgentContext<Any?>) -> Unit)? = null,
    ): this(retryPolicy, maxSteps, onStep, counter = AtomicInteger(), graphStack = ArrayDeque())

    fun graphPathSnapshot(): List<String> = graphStack.toList()

    suspend fun <T> withGraphScope(name: String, block: suspend () -> T): T {
        graphStack.addLast(name)
        return try {
            block()
        } finally {
            graphStack.removeLast()
        }
    }
}

data class RetryPolicy(
    val maxAttempts: Int = 2,
    val shouldRetry: suspend (
        error: Throwable,
        ctx: AgentContext<*>,
        node: Node<*, *>?,
        attempt: Int
    ) -> Boolean = { error, _, _, _ -> error is GigaException }
)
