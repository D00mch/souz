package ru.souz.backend.permission.service

import java.util.UUID
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.permission.model.PermissionDecision
import ru.souz.backend.permission.model.PermissionRequest

data class PermissionDecisionResult(
    val permissionRequest: PermissionRequest,
    val execution: AgentExecution,
)

/** User-scoped durable permission workflow boundary consumed by the HTTP API. */
interface PermissionService {
    /** Lists pending requests after verifying that [chatId] is owned by [userId]. */
    suspend fun listPending(
        userId: String,
        chatId: UUID,
    ): List<PermissionRequest>

    /**
     * Resolves one request and queues its execution for asynchronous continuation.
     *
     * Implementations must make repeated identical decisions idempotent, reject conflicting or
     * cancelled decisions, and hide both missing and foreign-owned IDs behind the same not-found
     * result.
     */
    suspend fun decide(
        userId: String,
        permissionRequestId: UUID,
        decision: PermissionDecision,
    ): PermissionDecisionResult
}
