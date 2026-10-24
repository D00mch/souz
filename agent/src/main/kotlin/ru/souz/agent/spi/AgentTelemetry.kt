package ru.souz.agent.spi

fun interface AgentTelemetry {
    fun recordToolExecution(event: AgentToolExecutionEvent)

    fun toolExecutionStarted(functionName: String) = Unit

    companion object {
        val NONE = AgentTelemetry { }
    }
}

data class AgentToolExecutionEvent(
    val functionName: String,
    val toolCategory: String? = null,
    val argumentKeys: List<String>,
    val durationMs: Long,
    val failure: Throwable? = null,
) {
    val success: Boolean get() = failure == null
    val errorType: String? get() = failure?.let { it::class.simpleName }
}
