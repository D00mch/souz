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
)

class GraphRuntime private constructor(
    val retryPolicy: RetryPolicy,
    val maxSteps: Int,
    val onStep: ((step: StepInfo, node: Node<Any?, Any?>, from: AgentContext<Any?>, to: AgentContext<Any?>) -> Unit)? = null,
    val counter: AtomicInteger
) {
    constructor(
        retryPolicy: RetryPolicy,
        maxSteps: Int,
        onStep: ((step: StepInfo, node: Node<Any?, Any?>, from: AgentContext<Any?>, to: AgentContext<Any?>) -> Unit)? = null,
    ): this(retryPolicy, maxSteps, onStep, counter = AtomicInteger())
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
