package ru.souz.agent.session

import com.fasterxml.jackson.databind.ObjectMapper
import ru.souz.agent.engine.AgentContext
import ru.souz.agent.engine.Node
import ru.souz.agent.engine.StepInfo
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Thread safe, but only one task at a time.
 * Use [NODE_NAME_CLASSIFY] to support chosen categories visualization
 */
class GraphSessionService(
    private val repository: GraphSessionRepository, private val logObjectMapper: ObjectMapper
) {
    companion object {
        const val NODE_NAME_CLASSIFY = "classify"
        private const val DATA_KEY_SELECTED_CATEGORIES = "selectedCategories"
        private const val DATA_KEY_INPUT = "input"
        private const val DATA_KEY_ACTIVE_TOOLS = "activeTools"
        private const val DATA_KEY_IN = "in"
        private const val DATA_KEY_OUT = "out"
    }

    private val currentSessionId = AtomicReference<String?>(null)
    private val startTime = AtomicLong(0)
    private val initialInput = AtomicReference("")
    private val steps = Collections.synchronizedList(mutableListOf<GraphStepRecord>())

    fun startTask(input: String) {
        currentSessionId.set(UUID.randomUUID().toString())
        startTime.set(System.currentTimeMillis())
        initialInput.set(input)
        steps.clear()
    }

    fun onStep(step: StepInfo, node: Node<*, *>, from: AgentContext<*>, to: AgentContext<*>) {
        if (currentSessionId.get() == null) return
        if (step.isSubgraph) return

        val prettyInput = try {
            logObjectMapper.writeValueAsString(from.input)
        } catch (e: Exception) {
            "Error serializing input: ${e.message}"
        }

        val prettyOutput = try {
            logObjectMapper.writeValueAsString(to.input)
        } catch (e: Exception) {
            "Error serializing output: ${e.message}"
        }

        val debugData = try {
            val baseData = mutableMapOf<String, Any?>(
                DATA_KEY_IN to contextDebugData(from),
                DATA_KEY_OUT to contextDebugData(to),
            )

            if (node.name.lowercase().contains(NODE_NAME_CLASSIFY)) {
                val toToolNames = to.activeToolNames().toSet()
                val selectedCategories = to.settings.toolsByCategory
                    .filter { (_, tools) -> tools.keys.any { it in toToolNames } }
                    .keys
                    .map { it.name }
                if (selectedCategories.isNotEmpty()) {
                    baseData[DATA_KEY_SELECTED_CATEGORIES] = selectedCategories
                }
            }

            logObjectMapper.writeValueAsString(baseData)
        } catch (e: Exception) {
            logObjectMapper.writeValueAsString(mapOf("error" to e.toString()))
        }

        val newMessages = to.history.filter { msg -> !from.history.contains(msg) }

        val historyDelta = if (newMessages.isNotEmpty()) {
            try {
                logObjectMapper.writeValueAsString(newMessages)
            } catch (e: Exception) {
                logObjectMapper.writeValueAsString(mapOf("error" to e.toString()))
            }
        } else null

        steps.add(
            GraphStepRecord(
                stepIndex = step.index,
                nodeName = node.name,
                timestamp = System.currentTimeMillis(),
                inputSummary = prettyInput,
                outputSummary = prettyOutput,
                data = debugData,
                addedHistory = historyDelta
            )
        )
    }

    private fun contextDebugData(ctx: AgentContext<*>): Map<String, Any?> = mapOf(
        DATA_KEY_INPUT to ctx.input,
        DATA_KEY_ACTIVE_TOOLS to ctx.activeToolNames()
    )

    private fun AgentContext<*>.activeToolNames(): List<String> = activeTools
        .map { it.name }
        .distinct()
        .sorted()

    fun finishTask() {
        val sessionId = currentSessionId.getAndSet(null) ?: return

        val stepsSnapshot = ArrayList(steps)

        val session = GraphSession(
            id = sessionId,
            startTime = startTime.get(),
            endTime = System.currentTimeMillis(),
            initialInput = initialInput.get(),
            steps = stepsSnapshot
        )
        repository.save(session)
    }
}
