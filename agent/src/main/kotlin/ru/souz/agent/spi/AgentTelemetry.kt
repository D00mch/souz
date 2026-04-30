package ru.souz.agent.spi

fun interface AgentTelemetry {
    fun recordToolExecution(event: AgentToolExecutionEvent)

    companion object {
        val NONE = AgentTelemetry { }
    }
}

data class AgentToolExecutionEvent(
    val appSessionId: String? = null,
    val conversationId: String? = null,
    val requestId: String? = null,
    val requestSource: String? = null,
    val model: String? = null,
    val provider: String? = null,
    val functionName: String,
    val toolCategory: String? = null,
    val argumentKeys: List<String>,
    val durationMs: Long,
    val success: Boolean,
    val errorType: String? = null,
)
