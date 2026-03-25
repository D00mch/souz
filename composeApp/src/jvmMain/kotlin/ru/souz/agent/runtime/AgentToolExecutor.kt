package ru.souz.agent.runtime

import org.slf4j.LoggerFactory
import java.util.UUID
import ru.souz.agent.engine.AgentSettings
import ru.souz.giga.GigaMessageRole
import ru.souz.giga.GigaRequest
import ru.souz.giga.GigaResponse
import ru.souz.giga.GigaToolSetup
import ru.souz.giga.PreparedGigaToolCall
import ru.souz.telemetry.TelemetryService
import ru.souz.tool.ToolActionDescriptor
import ru.souz.tool.ToolActionListener

class AgentToolExecutor(
    private val telemetryService: TelemetryService,
) {
    private val l = LoggerFactory.getLogger(AgentToolExecutor::class.java)

    suspend fun execute(
        settings: AgentSettings,
        functionCall: GigaResponse.FunctionCall,
        toolActionListener: ToolActionListener? = null,
    ): GigaRequest.Message {
        val fn: GigaToolSetup = settings.tools.byName[functionCall.name] ?: return GigaRequest.Message(
            role = GigaMessageRole.function,
            content = """{"result":"no such function ${functionCall.name}"}""",
        )

        l.info("Executing tool: ${fn.fn.name}, arguments: ${functionCall.arguments}")
        val startedAtMs = System.currentTimeMillis()
        val toolCategory = settings.tools.categoryByName[functionCall.name]
        val actionId = UUID.randomUUID().toString()
        return try {
            val preparedCall = fn.prepare(functionCall)
            notifyToolAction(toolActionListener, actionId, preparedCall.actionDescriptor)
            try {
                preparedCall.execute().also {
                    notifyToolAction(toolActionListener, actionId, preparedCall, success = true)
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
                notifyToolAction(toolActionListener, actionId, preparedCall, success = false)
                throw e
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

    private fun notifyToolAction(
        toolActionListener: ToolActionListener?,
        actionId: String,
        actionDescriptor: ToolActionDescriptor?,
        success: Boolean? = null,
    ) {
        if (actionDescriptor == null) return
        runCatching {
            when (success) {
                null -> toolActionListener?.onToolStarted(actionId, actionDescriptor)
                else -> toolActionListener?.onToolFinished(actionId, success)
            }
        }.onFailure { e ->
            l.warn("Tool action listener failed for actionId={}, success={}", actionId, success, e)
        }
    }

    private fun notifyToolAction(
        toolActionListener: ToolActionListener?,
        actionId: String,
        preparedCall: PreparedGigaToolCall,
        success: Boolean? = null,
    ) {
        notifyToolAction(toolActionListener, actionId, preparedCall.actionDescriptor, success)
    }
}
