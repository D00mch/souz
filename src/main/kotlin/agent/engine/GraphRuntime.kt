package com.dumch.agent.engine

import com.dumch.giga.GigaException
import kotlinx.coroutines.CancellationException
import kotlin.math.min

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
     * Sequential index of the executed node within the current graph run (starting from 1).
     */
    val index: Int,
    /**
     * Depth of the node in the traversal tree. This matches the previous "step" parameter.
     */
    val depth: Int,
)

data class GraphRuntime(
    val retryPolicy: RetryPolicy,
    val maxSteps: Int,
    val onStep: ((step: StepInfo, node: Node<Any?, Any?>, ctx: AgentContext<Any?>) -> Unit)? = null,
) {
    fun forSubgraph(localMaxSteps: Int): GraphRuntime = copy(maxSteps = min(maxSteps, localMaxSteps))
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
