package ru.souz.backend.permission.repository

import java.time.Instant
import java.util.UUID
import ru.souz.backend.events.model.AgentEvent
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.AgentExecutionUsage
import ru.souz.backend.permission.model.PermissionDecision
import ru.souz.backend.permission.model.PermissionRequest

enum class PermissionCheckpointPhase(val value: String) {
    BATCH_READY("batch_ready"),
    WAITING_PERMISSION("waiting_permission"),
    RESUME_QUEUED("resume_queued"),
    RESUME_CLAIMED("resume_claimed"),
    GRAPH_RESUMING("graph_resuming"),
}

enum class PermissionInvocationPhase(val value: String) {
    PLANNED("planned"),
    INVOKING("invoking"),
    WAITING_PERMISSION("waiting_permission"),
    RESUME_CLAIMED("resume_claimed"),
    EXECUTING("executing"),
    RESULT_STORED("result_stored"),
    FAILED("failed"),
}

data class AgentExecutionCheckpointRecord(
    val executionId: UUID,
    val userId: String,
    val chatId: UUID,
    val schemaVersion: Int,
    val revision: Long,
    val phase: PermissionCheckpointPhase,
    val contextJson: String,
    val batchJson: String,
    val nextOrdinal: Int,
    val baseStateRowVersion: Long,
    val compatibilityKey: String,
    val leaseToken: UUID?,
    val leaseExpiresAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val rowVersion: Long,
)

data class AgentToolInvocationRecord(
    val executionId: UUID,
    val invocationId: UUID,
    val userId: String,
    val chatId: UUID,
    val batchRevision: Long,
    val ordinal: Int,
    val providerCallId: String?,
    val toolName: String,
    val argumentsJson: String,
    val argumentsHash: String,
    val toolDefinitionHash: String,
    val phase: PermissionInvocationPhase,
    val resultMessageJson: String?,
    val errorCode: String?,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val updatedAt: Instant,
)

data class PlannedToolInvocation(
    val invocationId: UUID,
    val ordinal: Int,
    val providerCallId: String?,
    val toolName: String,
    val argumentsJson: String,
    val argumentsHash: String,
    val toolDefinitionHash: String,
)

data class SaveToolBatchCommand(
    val executionId: UUID,
    val userId: String,
    val chatId: UUID,
    val schemaVersion: Int,
    val revision: Long,
    val contextJson: String,
    val batchJson: String,
    val nextOrdinal: Int = 0,
    val baseStateRowVersion: Long,
    val compatibilityKey: String,
    val invocations: List<PlannedToolInvocation>,
    val now: Instant = Instant.now(),
)

data class ParkPermissionCommand(
    val executionId: UUID,
    val userId: String,
    val chatId: UUID,
    val invocationId: UUID,
    val toolCallId: String?,
    val toolName: String,
    val description: String,
    val displayParams: Map<String, String>,
    val promptHash: String,
    val usage: AgentExecutionUsage,
    val now: Instant = Instant.now(),
)

data class PermissionWorkflowTransition(
    val permissionRequest: PermissionRequest,
    val execution: AgentExecution,
    val event: AgentEvent?,
)

sealed interface StoredPermissionDecisionResult {
    data class Updated(val transition: PermissionWorkflowTransition) : StoredPermissionDecisionResult
    data class Idempotent(val permissionRequest: PermissionRequest, val execution: AgentExecution) : StoredPermissionDecisionResult
    data object NotFound : StoredPermissionDecisionResult
    data object Conflict : StoredPermissionDecisionResult
}

data class ClaimedPermissionContinuation(
    val checkpoint: AgentExecutionCheckpointRecord,
    val invocation: AgentToolInvocationRecord,
    val permissionRequest: PermissionRequest?,
    val execution: AgentExecution,
)

/** Transactional storage boundary for durable permission suspension and continuation. */
interface PermissionWorkflowRepository {
    suspend fun saveToolBatch(command: SaveToolBatchCommand): AgentExecutionCheckpointRecord

    suspend fun beginInvocation(
        executionId: UUID,
        invocationId: UUID,
        now: Instant = Instant.now(),
    ): AgentToolInvocationRecord

    suspend fun park(command: ParkPermissionCommand): PermissionWorkflowTransition

    suspend fun listPending(userId: String, chatId: UUID): List<PermissionRequest>

    suspend fun getOwned(userId: String, permissionRequestId: UUID): PermissionRequest?

    suspend fun decide(
        userId: String,
        permissionRequestId: UUID,
        decision: PermissionDecision,
        now: Instant = Instant.now(),
    ): StoredPermissionDecisionResult

    suspend fun claimReady(
        executionId: UUID,
        leaseToken: UUID,
        leaseExpiresAt: Instant,
        now: Instant = Instant.now(),
    ): ClaimedPermissionContinuation?

    suspend fun markExecuting(
        permissionRequestId: UUID,
        leaseToken: UUID,
        now: Instant = Instant.now(),
    ): Boolean

    suspend fun isClaimActive(
        executionId: UUID,
        leaseToken: UUID,
        now: Instant = Instant.now(),
    ): Boolean

    suspend fun storeToolResult(
        executionId: UUID,
        invocationId: UUID,
        resultMessageJson: String,
        contextJson: String,
        nextOrdinal: Int,
        now: Instant = Instant.now(),
    ): AgentExecutionCheckpointRecord

    suspend fun markGraphResuming(
        executionId: UUID,
        contextJson: String,
        now: Instant = Instant.now(),
    ): AgentExecutionCheckpointRecord

    suspend fun getCheckpoint(executionId: UUID): AgentExecutionCheckpointRecord?

    suspend fun listRecoveryCandidates(now: Instant = Instant.now()): List<AgentExecutionCheckpointRecord>

    /**
     * Moves a checkpoint proven safe by startup reconciliation back to the queued state.
     *
     * Ordinary dispatch must never reclaim a RUNNING execution: its worker may be between two
     * durable checkpoint writes. Recovery is the sole boundary allowed to make that persisted
     * work claimable again after verifying that no tool effect is in flight.
     */
    suspend fun requeueForRecovery(
        executionId: UUID,
        now: Instant = Instant.now(),
    ): Boolean

    suspend fun cancelPendingForExecution(
        userId: String,
        chatId: UUID,
        executionId: UUID,
        now: Instant = Instant.now(),
    ): List<AgentEvent>

    suspend fun failUnknownOutcome(
        executionId: UUID,
        now: Instant = Instant.now(),
    ): List<AgentEvent>

    suspend fun failCheckpoint(
        executionId: UUID,
        errorCode: String,
        errorMessage: String,
        now: Instant = Instant.now(),
    ): List<AgentEvent>

    suspend fun deleteCheckpoint(executionId: UUID)
}
