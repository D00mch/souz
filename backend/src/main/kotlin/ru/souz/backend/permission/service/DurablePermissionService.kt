package ru.souz.backend.permission.service

import io.ktor.http.HttpStatusCode
import java.util.UUID
import ru.souz.backend.chat.repository.ChatRepository
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.http.BackendV1Exception
import ru.souz.backend.permission.model.PermissionDecision
import ru.souz.backend.permission.model.PermissionRequest
import ru.souz.backend.permission.repository.PermissionWorkflowRepository
import ru.souz.backend.permission.repository.StoredPermissionDecisionResult

fun interface PermissionContinuationDispatcher {
    suspend fun wake(executionId: UUID)

    companion object {
        val NONE = PermissionContinuationDispatcher { }
    }
}

/** Public permission API backed by the transactional durable workflow. */
class DurablePermissionService(
    private val chatRepository: ChatRepository,
    private val workflowRepository: PermissionWorkflowRepository,
    private val eventService: AgentEventService,
    private val continuationDispatcher: PermissionContinuationDispatcher = PermissionContinuationDispatcher.NONE,
) : PermissionService {
    override suspend fun listPending(userId: String, chatId: UUID): List<PermissionRequest> {
        if (chatRepository.get(userId, chatId) == null) {
            throw BackendV1Exception(
                status = HttpStatusCode.NotFound,
                code = "chat_not_found",
                message = "Chat not found.",
            )
        }
        return workflowRepository.listPending(userId, chatId)
    }

    override suspend fun decide(
        userId: String,
        permissionRequestId: UUID,
        decision: PermissionDecision,
    ): PermissionDecisionResult = when (
        val result = workflowRepository.decide(userId, permissionRequestId, decision)
    ) {
        StoredPermissionDecisionResult.NotFound -> throw BackendV1Exception(
            status = HttpStatusCode.NotFound,
            code = "permission_request_not_found",
            message = "Permission request not found.",
        )

        StoredPermissionDecisionResult.Conflict -> throw BackendV1Exception(
            status = HttpStatusCode.Conflict,
            code = "permission_decision_conflict",
            message = "Permission request cannot accept that decision.",
        )

        is StoredPermissionDecisionResult.Idempotent -> {
            // The first request may have committed before event publication or dispatch failed.
            // Repair a missed dispatch only while the execution is still queued. A RUNNING
            // continuation may be between checkpoint writes and must never be reclaimed.
            if (result.execution.status == AgentExecutionStatus.QUEUED) {
                continuationDispatcher.wake(result.execution.id)
            }
            PermissionDecisionResult(result.permissionRequest, result.execution)
        }

        is StoredPermissionDecisionResult.Updated -> {
            result.transition.event?.let { eventService.publishPersisted(it) }
            continuationDispatcher.wake(result.transition.execution.id)
            PermissionDecisionResult(
                permissionRequest = result.transition.permissionRequest,
                execution = result.transition.execution,
            )
        }
    }
}
