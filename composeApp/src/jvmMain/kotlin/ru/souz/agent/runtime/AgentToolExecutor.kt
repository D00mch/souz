package ru.souz.agent.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.slf4j.LoggerFactory
import ru.souz.agent.engine.AgentSettings
import ru.souz.llms.GigaMessageRole
import ru.souz.llms.GigaRequest
import ru.souz.llms.GigaResponse
import ru.souz.llms.giga.GigaToolSetup
import ru.souz.telemetry.TelemetryService

class AgentToolExecutor(
    private val telemetryService: TelemetryService,
) {
    private val l = LoggerFactory.getLogger(AgentToolExecutor::class.java)
    private val _toolInvocations = MutableSharedFlow<GigaResponse.FunctionCall>(extraBufferCapacity = 32)

    val toolInvocations: Flow<GigaResponse.FunctionCall> = _toolInvocations.asSharedFlow()

    suspend fun execute(
        settings: AgentSettings,
        functionCall: GigaResponse.FunctionCall,
    ): GigaRequest.Message {
        val fn: GigaToolSetup = settings.tools.byName[functionCall.name] ?: return GigaRequest.Message(
            role = GigaMessageRole.function,
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
