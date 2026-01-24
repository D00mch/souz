package ru.gigadesk.agent.session

import com.fasterxml.jackson.databind.ObjectMapper
import ru.gigadesk.agent.engine.AgentContext
import ru.gigadesk.agent.engine.Node
import ru.gigadesk.agent.engine.StepInfo
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Thread safe, but only one task at a time.
 */
class GraphSessionService(
    private val repository: GraphSessionRepository, private val logObjectMapper: ObjectMapper
) {
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
            logObjectMapper.writeValueAsString(mapOf("in" to from.input, "out" to to.input))
        } catch (e: Exception) {
            "{}"
        }

        steps.add(
            GraphStepRecord(
                stepIndex = step.index,
                nodeName = node.name,
                timestamp = System.currentTimeMillis(),
                inputSummary = prettyInput.take(500),
                outputSummary = prettyOutput.take(500),
                data = debugData
            )
        )
    }

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
