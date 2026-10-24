package ru.souz.agent.runtime

import java.util.UUID
import kotlin.math.max
import ru.souz.agent.state.AgentSettings
import ru.souz.agent.spi.AgentTelemetry
import ru.souz.agent.spi.AgentToolExecutionEvent
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.ToolInvocationMeta

class AgentToolExecutor(
    private val telemetry: AgentTelemetry = AgentTelemetry.NONE,
) {
    // Completion accounting also runs when cancellation prevents failure-event delivery.
    @Suppress("SuspendFunSwallowedCancellation")
    suspend fun execute(
        settings: AgentSettings,
        functionCall: LLMResponse.FunctionCall,
        meta: ToolInvocationMeta = ToolInvocationMeta.localDefault(),
        toolCallId: String? = null,
        eventSink: AgentRuntimeEventSink = AgentRuntimeEventSink.NONE,
    ): LLMRequest.Message {
        val startedAtNanos = System.nanoTime()
        val runtimeToolCallId = toolCallId ?: UUID.randomUUID().toString()
        val toolCategoryName = settings.tools.categoryByName[functionCall.name]?.name
        telemetry.toolExecutionStarted(functionCall.name)
        var failure: Throwable? = null
        try {
            eventSink.emit(
                AgentRuntimeEvent.ToolCallStarted(runtimeToolCallId, functionCall.name, functionCall.arguments)
            )
            val tool = settings.tools.byName[functionCall.name] ?: throw UnknownTool()
            return tool.invoke(functionCall, meta).also {
                eventSink.emit(
                    AgentRuntimeEvent.ToolCallFinished(runtimeToolCallId, functionCall.name, it.content, durationMsSince(startedAtNanos))
                )
            }
        } catch (error: Throwable) {
            failure = error
            eventSink.emit(
                AgentRuntimeEvent.ToolCallFailed(runtimeToolCallId, functionCall.name, error, durationMsSince(startedAtNanos))
            )
            if (error is UnknownTool) return LLMRequest.Message(
                role = LLMMessageRole.function,
                content = """{"result":"no such function ${functionCall.name}"}""",
            )
            throw error
        } finally {
            recordToolExecution(functionCall, toolCategoryName, startedAtNanos, failure)
        }
    }

    private fun recordToolExecution(
        functionCall: LLMResponse.FunctionCall,
        toolCategoryName: String?,
        startedAtNanos: Long,
        failure: Throwable?,
    ) {
        telemetry.recordToolExecution(
            AgentToolExecutionEvent(
                functionName = functionCall.name,
                toolCategory = toolCategoryName,
                argumentKeys = functionCall.arguments.keys.sorted(),
                durationMs = durationMsSince(startedAtNanos),
                failure = failure,
            )
        )
    }
}

private fun durationMsSince(startedAtNanos: Long): Long =
    max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L)

private class UnknownTool : IllegalStateException("UnknownTool")
