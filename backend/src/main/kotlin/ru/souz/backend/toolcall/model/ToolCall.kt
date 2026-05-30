package ru.souz.backend.toolcall.model

import java.time.Instant

data class ToolCall(
    val userId: String,
    val chatId: String,
    val executionId: String,
    val toolCallId: String,
    val name: String,
    val status: ToolCallStatus,
    val argumentsJson: String,
    val resultPreview: String? = null,
    val error: String? = null,
    val startedAt: Instant,
    val finishedAt: Instant? = null,
    val durationMs: Long? = null,
)

enum class ToolCallStatus(val value: String) {
    RUNNING("running"),
    FINISHED("finished"),
    FAILED("failed"),
}
