package com.dumch.agent.engine

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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.math.min

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

internal data class EngineRuntime(
    val retryPolicy: RetryPolicy,
    val maxSteps: Int,
) {
    fun forSubgraph(localMaxSteps: Int): EngineRuntime = copy(maxSteps = min(maxSteps, localMaxSteps))
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
        val coroutineContext = currentCoroutineContext()
        val runtime = EngineRuntime(retryPolicy, maxSteps)

        try {
            while (q.isNotEmpty() && coroutineContext.isActive) {
                coroutineContext.ensureActive()
                if (processed >= maxSteps) error("Engine maxSteps ($maxSteps) reached — potential loop")

                val frame = q.removeFirst()
                val outCtx = executeWithRetry(frame.node, frame.ctx, frame.depth, runtime)
                onStep?.invoke(frame.depth, frame.node, outCtx)
                lastCtx = outCtx

                if (stopPredicate?.invoke(frame.node, outCtx) == true) return outCtx

                val nextNodes = frame.node.resolveNext(outCtx)
                if (nextNodes.isEmpty()) {
                    leaves += outCtx
                } else {
                    for (child in nextNodes) {
                        @Suppress("UNCHECKED_CAST")
                        q.add(Frame(child as Node<Any?, Any?>, outCtx, frame.depth + 1))
                    }
                }
                processed++
            }
            coroutineContext.ensureActive()
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
        runtime: EngineRuntime,
    ): AgentContext<Any?> {
        var attempt = 0
        var lastError: Throwable? = null
        while (attempt < retryPolicy.maxAttempts) {
            attempt++
            try {
                return node.execute(inCtx, runtime)
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
    ): EngineRun {
        @Suppress("UNCHECKED_CAST")
        val initial = seed as AgentContext<Any?>
        val updates = MutableSharedFlow<AgentContext<*>>(replay = 1)
        updates.tryEmit(initial)
        val job = scope.async {
            try {
                val result = run(initial, maxSteps) { step, node, ctx ->
                    onStep?.invoke(step, node, ctx)
                    updates.tryEmit(ctx)
                }
                updates.tryEmit(result)
                result
            } catch (cancel: EngineCancellation) {
                updates.tryEmit(cancel.lastContext)
                cancel.lastContext
            }
        }
        return EngineRunImpl(job, updates)
    }
}

interface EngineRun {
    val updates: Flow<AgentContext<*>>
    fun stop(cause: CancellationException? = null)
    suspend fun await(): AgentContext<*>
}

private class EngineRunImpl(
    private val deferred: Deferred<AgentContext<*>>,
    updatesFlow: MutableSharedFlow<AgentContext<*>>,
) : EngineRun {
    override val updates: Flow<AgentContext<*>> = updatesFlow

    override fun stop(cause: CancellationException?) {
        deferred.cancel(cause)
    }

    override suspend fun await(): AgentContext<*> = deferred.await()
}

/*

                                            Test realm below

 */

private fun callLlmMock(ctx: AgentContext<String>): AgentContext<String> {
    val text = ctx.input.trim()
    if (text == "What is the weather today?") return ctx.map { "tool" }
    return ctx.map { "Response: $it" }
}

private fun callToolMock(ctx: AgentContext<String>): AgentContext<String> {
    return ctx.map { "The weather is fine!" }
}

suspend fun main() {
    val userInputNode = Node<String, String>("userInput") { it }
    val llmCallNode = Node("llmCall") { callLlmMock(it) }
    val llmToolUseNode = Node("llmToolUse") { callToolMock(it) }
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
