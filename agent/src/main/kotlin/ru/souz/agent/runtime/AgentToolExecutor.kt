package ru.souz.agent.runtime

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
        meta: ToolInvocationMeta = ToolInvocationMeta.Empty,
    ): LLMRequest.Message {
        _toolInvocations.tryEmit(functionCall)
        val startedAtMs = System.currentTimeMillis()
        val toolCategoryName = settings.tools.categoryByName[functionCall.name]?.name
        val logContext = currentCoroutineContext()[AgentExecutionLogContext.Element]?.value
        logContext?.incrementToolExecutionCount()
        val fn: LLMToolSetup = settings.tools.byName[functionCall.name] ?: return LLMRequest.Message(
            role = LLMMessageRole.function,
            content = """{"result":"no such function ${functionCall.name}"}""",
        ).also {
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
            fn.invoke(functionCall, meta).also {
                recordToolExecution(
                    functionCall = functionCall,
                    toolCategoryName = toolCategoryName,
                    startedAtMs = startedAtMs,
                    logContext = logContext,
                    success = true,
                )
            }
        } catch (e: Exception) {
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
