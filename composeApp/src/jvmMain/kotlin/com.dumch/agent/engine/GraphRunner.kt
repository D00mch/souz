package com.dumch.agent.engine

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
        seed: AgentContext<Any?>,
        runtime: GraphRuntime,
        definition: GraphDefinition,
        stopPredicate: ((Node<Any?, Any?>, AgentContext<Any?>) -> Boolean)? = null,
    ): AgentContext<Any?> {
        val queue = ArrayDeque<Frame>().apply { add(Frame(start, seed, 0)) }
        val leaves = mutableListOf<AgentContext<*>>()
        var lastCtx: AgentContext<Any?> = seed
        val coroutineContext = currentCoroutineContext()

        try {
            while (queue.isNotEmpty() && coroutineContext.isActive) {
                coroutineContext.ensureActive()
                if (runtime.counter.get() >= runtime.maxSteps) {
                    error("Graph maxSteps (${runtime.maxSteps}) reached — potential loop")
                }

                val frame = queue.removeFirst()
                val outCtx = executeWithRetry(frame.node, frame.ctx, runtime)
                val stepInfo = StepInfo(currentGraphIndex = frame.depth, index = runtime.counter.get())
                runtime.onStep?.invoke(stepInfo, frame.node, outCtx)
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

        @Suppress("UNCHECKED_CAST")
        return leaves.lastOrNull() as? AgentContext<Any?> ?: lastCtx
    }

    private suspend fun executeWithRetry(
        node: Node<Any?, Any?>,
        inCtx: AgentContext<Any?>,
        runtime: GraphRuntime,
    ): AgentContext<Any?> {
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
        val ctx: AgentContext<Any?>,
        val depth: Int,
    )
}

/*
                    TEST CODE
 */

private fun callLlmMock(ctx: AgentContext<String>): AgentContext<String> {
    val text = ctx.input.trim()
    if (text == "What is the weather today?") return ctx.map { "composeApp/src/jvmMain/kotlin/com.dumch/tooloseApp/src/jvmMain/kotlin/com.dumch/tool" }
    return ctx.map { "Response: $it" }
}

private fun callToolMock(ctx: AgentContext<String>): AgentContext<String> {
    return ctx.map { "The weather is fine!" }
}

suspend fun main() {
    val userInputNode = Node<String, String>("userInput") { ctx -> ctx.map { readln() } }
    val llmCallNode = Node("llmCall") { callLlmMock(it) }
    val llmToolUseNode = Node("llmToolUse") { callToolMock(it) }
    val userOutputNode = Node<String, String>("userOutput") { it }

    val settings = AgentSettings("gpt5", 0.7f, emptyMap())
    val seed = AgentContext(input = "What is the weather today?", settings, emptyList(), emptyList(), "")

    val graph = buildGraph {
        nodeInput.edgeTo(userInputNode)
        userInputNode.edgeTo{ ctx ->
            when (ctx.input) {
                "exit", "finish" -> nodeFinish
                else -> llmCallNode
            }
        }
        llmToolUseNode.edgeTo(llmCallNode)
        llmCallNode.edgeTo { ctx ->
            when (ctx.input) {
                "composeApp/src/jvmMain/kotlin/com.dumch/tooloseApp/src/jvmMain/kotlin/com.dumch/tool" -> llmToolUseNode
                else -> userOutputNode
            }
        }
        userOutputNode.edgeTo(userInputNode)
    }
    graph.start(seed, onStep = { step, n, c ->
        println("step #${step.index} (depth ${step.currentGraphIndex}): node: ${n.name}, ctx: $c")
    })
}