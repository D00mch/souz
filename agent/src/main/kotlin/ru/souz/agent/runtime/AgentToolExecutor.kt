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
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta

class AgentToolExecutor(
    private val telemetry: AgentTelemetry = AgentTelemetry.NONE,
) {
    private val _toolInvocations = MutableSharedFlow<LLMResponse.FunctionCall>(extraBufferCapacity = 32)

    val toolInvocations: Flow<LLMResponse.FunctionCall> = _toolInvocations.asSharedFlow()

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
        eventSink.emit(
            AgentRuntimeEvent.ToolCallStarted(
                toolCallId = runtimeToolCallId,
                name = functionCall.name,
                arguments = functionCall.arguments,
            )
        )
        val fn: LLMToolSetup = settings.tools.byName[functionCall.name] ?: return LLMRequest.Message(
            role = LLMMessageRole.function,
            content = """{"result":"no such function ${functionCall.name}"}""",
        ).also {
            val error = UnknownTool("UnknownTool")
            eventSink.emit(
                AgentRuntimeEvent.ToolCallFailed(
                    toolCallId = runtimeToolCallId,
                    name = functionCall.name,
                    error = error,
                    durationMs = durationMsSince(startedAtNanos),
                )
            )
            recordToolExecution(
                functionCall = functionCall,
                toolCategoryName = toolCategoryName,
                startedAtNanos = startedAtNanos,
                logContext = logContext,
                success = false,
                errorType = error::class.simpleName,
            )
        }
        return try {
            fn.invoke(functionCall, meta).also {
                eventSink.emit(
                    AgentRuntimeEvent.ToolCallFinished(
                        toolCallId = runtimeToolCallId,
                        name = functionCall.name,
                        result = it.content,
                        durationMs = durationMsSince(startedAtNanos),
                    )
                )
                recordToolExecution(
                    functionCall = functionCall,
                    toolCategoryName = toolCategoryName,
                    startedAtNanos = startedAtNanos,
                    logContext = logContext,
                    success = true,
                )
            }
        } catch (e: Exception) {
            eventSink.emit(
                AgentRuntimeEvent.ToolCallFailed(
                    toolCallId = runtimeToolCallId,
                    name = functionCall.name,
                    error = e,
                    durationMs = durationMsSince(startedAtNanos),
                )
            )
            recordToolExecution(
                functionCall = functionCall,
                toolCategoryName = toolCategoryName,
                startedAtNanos = startedAtNanos,
                logContext = logContext,
                success = false,
                errorType = e::class.simpleName ?: e::class.qualifiedName?.substringAfterLast('.'),
            )
            throw e
        }
    }

    private fun recordToolExecution(
        functionCall: LLMResponse.FunctionCall,
        toolCategoryName: String?,
        startedAtNanos: Long,
        logContext: AgentExecutionLogContext?,
        success: Boolean,
        errorType: String? = null,
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
                success = success,
                errorType = errorType,
            )
        )
    }
}

private fun durationMsSince(startedAtNanos: Long): Long =
    max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L)

private class UnknownTool(message: String) : IllegalStateException(message)
