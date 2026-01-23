package ru.gigadesk.agent.planning

import kotlinx.serialization.Serializable

@Serializable
data class ExecutionPlan(
    val goal: String,
    val steps: List<PlanStep>,
    val currentStepIndex: Int = 0,
    val isApproved: Boolean = false,
)

@Serializable
data class PlanStep(
    val id: String,
    val toolName: String,
    val arguments: Map<String, String> = emptyMap(),
    val status: StepStatus = StepStatus.PENDING,
    val result: String? = null,
    val userApprovalRequired: Boolean = false
)

enum class StepStatus {
    PENDING,
    IN_PROGRESS,
    SUCCESS,
    DONE, // LLM sometimes outputs DONE instead of SUCCESS
    FAILED
}
