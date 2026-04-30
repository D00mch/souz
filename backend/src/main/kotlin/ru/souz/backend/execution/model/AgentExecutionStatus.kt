package ru.souz.backend.execution.model

enum class AgentExecutionStatus(val value: String) {
    QUEUED("queued"),
    RUNNING("running"),
    WAITING_CHOICE("waiting_choice"),
    CANCELLING("cancelling"),
    CANCELLED("cancelled"),
    COMPLETED("completed"),
    FAILED("failed"),
}
