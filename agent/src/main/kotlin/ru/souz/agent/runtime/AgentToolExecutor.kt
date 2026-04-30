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

class AgentToolExecutor(
    private val telemetry: AgentTelemetry = AgentTelemetry.NONE,
) {
    private val _toolInvocations = MutableSharedFlow<LLMResponse.FunctionCall>(extraBufferCapacity = 32)

    val toolInvocations: Flow<LLMResponse.FunctionCall> = _toolInvocations.asSharedFlow()

    suspend fun execute(
        settings: AgentSettings,
        functionCall: LLMResponse.FunctionCall,
    ): LLMRequest.Message {
        val fn: LLMToolSetup = settings.tools.byName[functionCall.name] ?: return LLMRequest.Message(
            role = LLMMessageRole.function,
            content = """{"result":"no such function ${functionCall.name}"}""",
        )

        _toolInvocations.tryEmit(functionCall)
        val startedAtMs = System.currentTimeMillis()
        val toolCategory = settings.tools.categoryByName[functionCall.name]
        val logContext = currentCoroutineContext()[AgentExecutionLogContext.Element]?.value
        logContext?.incrementToolExecutionCount()
        return try {
            fn.invoke(functionCall).also {
                telemetry.recordToolExecution(
                    AgentToolExecutionEvent(
                        appSessionId = logContext?.appSessionId,
                        conversationId = logContext?.conversationId,
                        requestId = logContext?.requestId,
                        requestSource = logContext?.requestSource,
                        model = logContext?.model,
                        provider = logContext?.provider,
                        functionName = functionCall.name,
                        toolCategory = toolCategory?.name,
                        argumentKeys = functionCall.arguments.keys.sorted(),
                        durationMs = System.currentTimeMillis() - startedAtMs,
                        success = true,
                    )
                )
            }
        } catch (e: Exception) {
            telemetry.recordToolExecution(
                AgentToolExecutionEvent(
                    appSessionId = logContext?.appSessionId,
                    conversationId = logContext?.conversationId,
                    requestId = logContext?.requestId,
                    requestSource = logContext?.requestSource,
                    model = logContext?.model,
                    provider = logContext?.provider,
                    functionName = functionCall.name,
                    toolCategory = toolCategory?.name,
                    argumentKeys = functionCall.arguments.keys.sorted(),
                    durationMs = System.currentTimeMillis() - startedAtMs,
                    success = false,
                    errorType = e::class.simpleName ?: e::class.qualifiedName?.substringAfterLast('.'),
                )
            )
            throw e
        }
    }
}
