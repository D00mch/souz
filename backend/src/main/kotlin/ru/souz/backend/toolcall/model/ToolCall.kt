package ru.souz.backend.toolcall.model

import java.time.Instant

data class ToolCall(
    val userId: String,
    val chatId: String,
    val executionId: String,
    val toolCallId: String,
    val name: String,
    val target: String = "souz",
    val deviceId: String? = null,
    val status: ToolCallStatus,
    val argumentsJson: String,
    val resultJson: String? = null,
    val errorJson: String? = null,
    val deadlineAt: Instant? = null,
    val resultPayloadHash: String? = null,
    val resultReceivedAt: Instant? = null,
    val startedAt: Instant,
    val finishedAt: Instant? = null,
    val durationMs: Long? = null,
)

enum class ToolCallStatus(val value: String) {
    RUNNING("running"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    TIMED_OUT("timed_out"),
}
