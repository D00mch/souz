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

fun AgentExecutionStatus.isActive(): Boolean =
    this == AgentExecutionStatus.QUEUED ||
        this == AgentExecutionStatus.RUNNING ||
        this == AgentExecutionStatus.WAITING_CHOICE ||
        this == AgentExecutionStatus.CANCELLING
