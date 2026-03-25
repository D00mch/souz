@file:OptIn(ExperimentalAtomicApi::class)

package ru.souz.agent.runtime

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import ru.souz.agent.AgentExecutionResult
import ru.souz.agent.GraphStepCallback
import ru.souz.agent.engine.AgentContext
import ru.souz.agent.engine.Graph
import ru.souz.tool.ToolActionListener
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException

internal interface GraphExecutionDelegate {
    fun cancelActiveJob()

    suspend fun executeWithTrace(
        graph: Graph<String, String>,
        ctx: AgentContext<String>,
        onStep: GraphStepCallback? = null,
        toolActionListener: ToolActionListener? = null,
    ): AgentExecutionResult
}

internal class GraphExecutionDelegateImpl(
    private val logObjectMapper: ObjectMapper,
    loggerClass: Class<*>,
) : GraphExecutionDelegate {
    private val logger = LoggerFactory.getLogger(loggerClass)
    private val runningJob = AtomicReference<Deferred<*>?>(null)

    override fun cancelActiveJob() {
        runningJob.load()?.cancel(CancellationException("Cancelled by facade"))
    }

    override suspend fun executeWithTrace(
        graph: Graph<String, String>,
        ctx: AgentContext<String>,
        onStep: GraphStepCallback?,
        toolActionListener: ToolActionListener?,
    ): AgentExecutionResult {
        cancelActiveJob()
        val newContext = coroutineScope {
            val result: Deferred<AgentContext<String>> = async {
                graph.start(
                    seed = ctx,
                    onStep = { step, node, from, to ->
                    val prettyInput = runCatching {
                        logObjectMapper.writeValueAsString(from.input)
                    }.getOrElse { from.input.toString() }
                    logger.debug("Step: {}, node: {}, input: {}", step.index, node.name, prettyInput)
                    onStep?.invoke(step, node, from, to)
                    },
                    toolActionListener = toolActionListener,
                )
            }
            runningJob.store(result)
            try {
                result.await()
            } finally {
                runningJob.compareAndSet(result, null)
            }
        }
        return AgentExecutionResult(output = newContext.input, context = newContext)
    }
}
