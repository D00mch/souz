package ru.souz.backend.events.model

enum class AgentEventType(val value: String) {
    MESSAGE_CREATED("message.created"),
    MESSAGE_DELTA("message.delta"),
    MESSAGE_COMPLETED("message.completed"),
    TOOL_CALL_STARTED("tool.call.started"),
    TOOL_CALL_FINISHED("tool.call.finished"),
    TOOL_CALL_FAILED("tool.call.failed"),
    CHOICE_REQUESTED("choice.requested"),
    CHOICE_ANSWERED("choice.answered"),
    EXECUTION_STARTED("execution.started"),
    EXECUTION_FINISHED("execution.finished"),
    EXECUTION_FAILED("execution.failed"),
    EXECUTION_CANCELLED("execution.cancelled"),
}
