package ru.souz.backend.execution.model

enum class AgentExecutionStatus(val value: String) {
    QUEUED("queued"),
    RUNNING("running"),
    WAITING_OPTION("waiting_option"),
    WAITING_PERMISSION("waiting_permission"),
    CANCELLING("cancelling"),
    CANCELLED("cancelled"),
    COMPLETED("completed"),
    FAILED("failed"),
}

fun AgentExecutionStatus.isActive(): Boolean =
    this == AgentExecutionStatus.QUEUED ||
        this == AgentExecutionStatus.RUNNING ||
        this == AgentExecutionStatus.WAITING_OPTION ||
        this == AgentExecutionStatus.WAITING_PERMISSION ||
        this == AgentExecutionStatus.CANCELLING
