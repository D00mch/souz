package ru.souz.agent.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.slf4j.LoggerFactory
import ru.souz.agent.state.AgentSettings
import ru.souz.agent.spi.AgentTelemetry
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup

class AgentToolExecutor(
    private val telemetryService: AgentTelemetry,
) {
    private val l = LoggerFactory.getLogger(AgentToolExecutor::class.java)
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
        l.info("Executing tool: ${fn.fn.name}, arguments: ${functionCall.arguments}")
        val startedAtMs = System.currentTimeMillis()
        val toolCategory = settings.tools.categoryByName[functionCall.name]
        return try {
            fn.invoke(functionCall).also {
                telemetryService.recordToolExecution(
                    functionName = functionCall.name,
                    functionArguments = functionCall.arguments,
                    toolCategory = toolCategory,
                    durationMs = System.currentTimeMillis() - startedAtMs,
                    success = true,
                    errorMessage = null,
                )
            }
        } catch (e: Exception) {
            l.error("Tool execution failure: ${fn.fn.name}, arguments: ${functionCall.arguments}", e)
            telemetryService.recordToolExecution(
                functionName = functionCall.name,
                functionArguments = functionCall.arguments,
                toolCategory = toolCategory,
                durationMs = System.currentTimeMillis() - startedAtMs,
                success = false,
                errorMessage = e::class.simpleName ?: "UnknownError",
            )
            throw e
        }
    }
}
