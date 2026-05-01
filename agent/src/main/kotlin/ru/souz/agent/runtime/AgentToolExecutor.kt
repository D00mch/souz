package ru.souz.agent.runtime

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID
import ru.souz.agent.state.AgentSettings
import ru.souz.agent.spi.AgentTelemetry
import ru.souz.agent.spi.AgentToolExecutionEvent
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup

class AgentToolExecutor(
    private val telemetry: AgentTelemetry = AgentTelemetry.NONE,
) {
    private val _toolInvocations = MutableSharedFlow<LLMResponse.FunctionCall>(extraBufferCapacity = 32)

    val toolInvocations: Flow<LLMResponse.FunctionCall> = _toolInvocations.asSharedFlow()

    suspend fun execute(
        settings: AgentSettings,
        functionCall: LLMResponse.FunctionCall,
        toolCallId: String? = null,
        eventSink: AgentRuntimeEventSink = AgentRuntimeEventSink.NONE,
    ): LLMRequest.Message {
        _toolInvocations.tryEmit(functionCall)
        val startedAtMs = System.currentTimeMillis()
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
            eventSink.emit(
                AgentRuntimeEvent.ToolCallFailed(
                    toolCallId = runtimeToolCallId,
                    name = functionCall.name,
                    error = "UnknownTool",
                    durationMs = System.currentTimeMillis() - startedAtMs,
                )
            )
            recordToolExecution(
                functionCall = functionCall,
                toolCategoryName = toolCategoryName,
                startedAtMs = startedAtMs,
                logContext = logContext,
                success = false,
                errorType = "UnknownTool",
            )
        }
        return try {
            fn.invoke(functionCall).also {
                eventSink.emit(
                    AgentRuntimeEvent.ToolCallFinished(
                        toolCallId = runtimeToolCallId,
                        name = functionCall.name,
                        resultPreview = it.content,
                        durationMs = System.currentTimeMillis() - startedAtMs,
                    )
                )
                recordToolExecution(
                    functionCall = functionCall,
                    toolCategoryName = toolCategoryName,
                    startedAtMs = startedAtMs,
                    logContext = logContext,
                    success = true,
                )
            }
        } catch (e: Exception) {
            eventSink.emit(
                AgentRuntimeEvent.ToolCallFailed(
                    toolCallId = runtimeToolCallId,
                    name = functionCall.name,
                    error = e.message ?: (e::class.simpleName ?: "ToolExecutionFailed"),
                    durationMs = System.currentTimeMillis() - startedAtMs,
                )
            )
            recordToolExecution(
                functionCall = functionCall,
                toolCategoryName = toolCategoryName,
                startedAtMs = startedAtMs,
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
        startedAtMs: Long,
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
                durationMs = System.currentTimeMillis() - startedAtMs,
                success = success,
                errorType = errorType,
            )
        )
    }
}
