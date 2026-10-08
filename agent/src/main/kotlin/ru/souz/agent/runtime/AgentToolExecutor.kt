package ru.souz.agent.runtime

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    private val _toolInvocations = MutableSharedFlow<LLMResponse.FunctionCall>(extraBufferCapacity = 32)

    val toolInvocations: Flow<LLMResponse.FunctionCall> = _toolInvocations.asSharedFlow()

    // Accounting runs even if cancellation prevents the failure event from being delivered.
    @Suppress("SuspendFunSwallowedCancellation")
    suspend fun execute(
        settings: AgentSettings,
        functionCall: LLMResponse.FunctionCall,
        meta: ToolInvocationMeta = ToolInvocationMeta.localDefault(),
        toolCallId: String? = null,
        eventSink: AgentRuntimeEventSink = AgentRuntimeEventSink.NONE,
    ): LLMRequest.Message {
        _toolInvocations.tryEmit(functionCall)
        val startedAtNanos = System.nanoTime()
        val runtimeToolCallId = toolCallId ?: UUID.randomUUID().toString()
        val toolCategoryName = settings.tools.categoryByName[functionCall.name]?.name
        val logContext = currentCoroutineContext()[AgentExecutionLogContext.Element]?.value
        logContext?.incrementToolExecutionCount()
        telemetry.toolExecutionStarted(functionCall.name)
        var failure: Throwable? = null
        try {
            eventSink.emit(AgentRuntimeEvent.ToolCallStarted(runtimeToolCallId, functionCall.name, functionCall.arguments))
            val fn = settings.tools.byName[functionCall.name] ?: throw UnknownTool("UnknownTool")
            return fn.invoke(functionCall, meta).also {
                eventSink.emit(AgentRuntimeEvent.ToolCallFinished(runtimeToolCallId, functionCall.name, it.content, durationMsSince(startedAtNanos)))
            }
        } catch (error: Throwable) {
            failure = error
            eventSink.emit(AgentRuntimeEvent.ToolCallFailed(runtimeToolCallId, functionCall.name, error, durationMsSince(startedAtNanos)))
            if (error is UnknownTool) return LLMRequest.Message(
                role = LLMMessageRole.function,
                content = """{"result":"no such function ${functionCall.name}"}""",
            )
            throw error
        } finally {
            recordToolExecution(functionCall, toolCategoryName, startedAtNanos, logContext, failure)
        }
    }

    private fun recordToolExecution(
        functionCall: LLMResponse.FunctionCall,
        toolCategoryName: String?,
        startedAtNanos: Long,
        logContext: AgentExecutionLogContext?,
        failure: Throwable?,
    ) {
        telemetry.recordToolExecution(
            AgentToolExecutionEvent(
                appSessionId = logContext?.appSessionId,
                conversationId = logContext?.conversationId,
                requestId = logContext?.requestId,
                requestSource = logContext?.requestSource,
                model = logContext?.model,
                provider = logContext?.provider,
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

private class UnknownTool(message: String) : IllegalStateException(message)
