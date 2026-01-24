package ru.gigadesk.agent.session

import com.fasterxml.jackson.databind.ObjectMapper
import ru.gigadesk.agent.engine.AgentContext
import ru.gigadesk.agent.engine.Node
import ru.gigadesk.agent.engine.StepInfo
import java.util.UUID

class GraphSessionService(
    private val repository: GraphSessionRepository,
    private val logObjectMapper: ObjectMapper
) {
    private var currentSessionId: String? = null
    private var startTime: Long = 0
    private var initialInput: String = ""
    private val steps = mutableListOf<GraphStepRecord>()

    fun startTask(input: String) {
        currentSessionId = UUID.randomUUID().toString()
        startTime = System.currentTimeMillis()
        initialInput = input
        steps.clear()
    }

    fun onStep(step: StepInfo, node: Node<*, *>, from: AgentContext<*>, to: AgentContext<*>) {
        if (currentSessionId == null) return

        val prettyInput = try {
            logObjectMapper.writeValueAsString(from.input)
        } catch (e: Exception) {
            "Error serializing input: ${e.message}"
        }

        val prettyOutput = try {
            logObjectMapper.writeValueAsString(to.input) // Context input is usually the "value" of the context
        } catch (e: Exception) {
            "Error serializing output: ${e.message}"
        }
        
        // Full data capture for debug
        val debugData = try {
            logObjectMapper.writeValueAsString(mapOf("in" to from.input, "out" to to.input))
        } catch (e: Exception) {
            "{}"
        }

        steps.add(GraphStepRecord(
            stepIndex = step.index,
            nodeName = node.name,
            timestamp = System.currentTimeMillis(),
            inputSummary = prettyInput.take(500),
            outputSummary = prettyOutput.take(500),
            data = debugData
        ))
    }

    fun finishTask() {
        val sessionId = currentSessionId ?: return
        
        val session = GraphSession(
            id = sessionId,
            startTime = startTime,
            endTime = System.currentTimeMillis(),
            initialInput = initialInput,
            steps = steps.toList()
        )
        repository.save(session)
        currentSessionId = null
    }
}
