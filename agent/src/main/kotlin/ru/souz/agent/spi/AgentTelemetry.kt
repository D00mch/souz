package ru.souz.agent.spi

import ru.souz.tool.ToolCategory

/**
 * Records tool execution telemetry for the host application.
 *
 * The agent runtime reports structured events through this interface instead of
 * depending on a concrete telemetry service.
 */
interface AgentTelemetry {
    /**
     * Records one tool execution attempt together with timing and outcome.
     */
    fun recordToolExecution(
        functionName: String,
        functionArguments: Map<String, Any>,
        toolCategory: ToolCategory?,
        durationMs: Long,
        success: Boolean,
        errorMessage: String?,
    )
}
