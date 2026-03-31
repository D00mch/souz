package ru.souz.graph

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import org.slf4j.Logger
import org.slf4j.LoggerFactory

internal class GraphRunner(
    private val logger: Logger = LoggerFactory.getLogger(GraphRunner::class.java),
) {

    suspend fun run(
        start: Node<Any?, Any?>,
        seed: Any?,
        runtime: GraphRuntime,
        definition: GraphDefinition,
        stopPredicate: ((Node<Any?, Any?>, Any?) -> Boolean)? = null,
    ): Any? {
        val queue = ArrayDeque<Frame>().apply { add(Frame(start, seed, 0)) }
        val leaves = mutableListOf<Any?>()
        var lastCtx: Any? = seed
        val coroutineContext = currentCoroutineContext()

        try {
            while (queue.isNotEmpty() && coroutineContext.isActive) {
                coroutineContext.ensureActive()
                if (runtime.counter.get() >= runtime.maxSteps) {
                    error("Graph maxSteps (${runtime.maxSteps}) reached — potential loop")
                }

                val frame = queue.removeFirst()
                val outCtx = executeWithRetry(frame.node, frame.ctx, runtime)
                val graphPath = runtime.graphPathSnapshot()
                val graphDepth = (graphPath.size - 1).coerceAtLeast(0)
                val stepInfo = StepInfo(
                    currentGraphIndex = frame.depth,
                    index = runtime.counter.get(),
                    graphName = graphPath.lastOrNull() ?: "Unknown",
                    graphDepth = graphDepth,
                )
                runtime.onStep?.invoke(stepInfo, frame.node, frame.ctx, outCtx)
                lastCtx = outCtx

                if (stopPredicate?.invoke(frame.node, outCtx) == true) return outCtx

                val nextNodes = definition.nextNodes(frame.node, outCtx)
                if (nextNodes.isEmpty()) {
                    leaves += outCtx
                } else {
                    for (child in nextNodes) {
                        @Suppress("UNCHECKED_CAST")
                        queue.add(Frame(child as Node<Any?, Any?>, outCtx, frame.depth + 1))
                    }
                }
                runtime.counter.incrementAndGet()
            }
        } catch (cancel: CancellationException) {
            throw GraphCancellation(lastCtx, cancel)
        }

        return leaves.lastOrNull() ?: lastCtx
    }

    private suspend fun executeWithRetry(
        node: Node<Any?, Any?>,
        inCtx: Any?,
        runtime: GraphRuntime,
    ): Any? {
        val policy = runtime.retryPolicy
        var attempt = 0
        var lastError: Throwable? = null
        while (attempt < policy.maxAttempts) {
            attempt++
            try {
                return node.execute(inCtx, runtime)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                lastError = t
                val shouldRetry = policy.shouldRetry(t, inCtx, node, attempt)
                val attemptsLeft = policy.maxAttempts - attempt
                if (!shouldRetry || attemptsLeft <= 0) break
                logger.warn(
                    "Node '{}' failed on attempt {} (depth {}), retrying ({} attempts left): {}",
                    node.name,
                    attempt,
                    runtime.counter.get(),
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
        val ctx: Any?,
        val depth: Int,
    )
}
