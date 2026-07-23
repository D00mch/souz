package ru.souz.backend.permission.model

import java.time.Instant
import java.util.UUID

enum class PermissionRequestStatus(val value: String) {
    PENDING("pending"),
    GRANTED("granted"),
    DENIED("denied"),
    CANCELLED("cancelled"),
}

enum class PermissionDecision(val value: String) {
    GRANT("grant"),
    DENY("deny"),
}

/**
 * Client-safe view of a durable permission request.
 *
 * Raw tool arguments, prompt hashes, and execution checkpoints deliberately do not belong in
 * this model. They remain private to the workflow coordinator and its repositories.
 */
data class PermissionRequest(
    val id: UUID,
    val userId: String,
    val chatId: UUID,
    val executionId: UUID,
    val invocationId: UUID,
    val toolName: String,
    val toolCallId: String?,
    val description: String,
    val displayParams: Map<String, String>,
    val status: PermissionRequestStatus,
    val createdAt: Instant,
    val resolvedAt: Instant?,
) {
    companion object {
        const val MAX_DESCRIPTION_LENGTH = 512
        const val MAX_DISPLAY_PARAMS = 16
        const val MAX_DISPLAY_PARAM_KEY_LENGTH = 64
        const val MAX_DISPLAY_PARAM_VALUE_LENGTH = 256
    }
}
