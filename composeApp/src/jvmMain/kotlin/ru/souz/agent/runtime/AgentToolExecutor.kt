package ru.souz.agent.runtime

import org.slf4j.LoggerFactory
import java.util.UUID
import ru.souz.agent.engine.AgentSettings
import ru.souz.giga.GigaMessageRole
import ru.souz.giga.GigaRequest
import ru.souz.giga.GigaResponse
import ru.souz.giga.GigaToolSetup
import ru.souz.telemetry.TelemetryService
import ru.souz.tool.ToolActionDescriptor

class AgentToolExecutor(
    private val telemetryService: TelemetryService,
) {
    private val l = LoggerFactory.getLogger(AgentToolExecutor::class.java)

    suspend fun execute(
        settings: AgentSettings,
        functionCall: GigaResponse.FunctionCall,
    ): GigaRequest.Message {
        val fn: GigaToolSetup = settings.tools.byName[functionCall.name] ?: return GigaRequest.Message(
            role = GigaMessageRole.function,
            content = """{"result":"no such function ${functionCall.name}"}""",
        )

        l.info("Executing tool: ${fn.fn.name}, arguments: ${functionCall.arguments}")
        val startedAtMs = System.currentTimeMillis()
        val toolCategory = settings.tools.categoryByName[functionCall.name]
        val actionId = UUID.randomUUID().toString()
        val actionDescriptor = fn.describeAction(functionCall)
        notifyToolAction(settings, actionId, actionDescriptor)
        return try {
            fn.invoke(functionCall).also {
                notifyToolAction(settings, actionId, actionDescriptor, success = true)
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
            notifyToolAction(settings, actionId, actionDescriptor, success = false)
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

    private fun notifyToolAction(
        settings: AgentSettings,
        actionId: String,
        actionDescriptor: ToolActionDescriptor?,
        success: Boolean? = null,
    ) {
        if (actionDescriptor == null) return
        runCatching {
            when (success) {
                null -> settings.toolActionListener?.onToolStarted(actionId, actionDescriptor)
                else -> settings.toolActionListener?.onToolFinished(actionId, success)
            }
        }.onFailure { e ->
            l.warn("Tool action listener failed for actionId={}, success={}", actionId, success, e)
        }
    }
}
