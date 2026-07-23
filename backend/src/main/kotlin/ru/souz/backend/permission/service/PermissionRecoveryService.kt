package ru.souz.backend.permission.service

import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.permission.repository.AgentExecutionCheckpointRecord
import ru.souz.backend.permission.repository.PermissionCheckpointPhase
import ru.souz.backend.permission.repository.PermissionWorkflowRepository

/** Reconciles durable permission checkpoints after process startup. */
class PermissionRecoveryService(
    private val featureFlags: BackendFeatureFlags,
    private val workflowRepository: PermissionWorkflowRepository,
    private val continuationDispatcher: PermissionContinuationDispatcher,
    private val eventService: AgentEventService,
    private val scope: CoroutineScope,
) {
    private val logger = LoggerFactory.getLogger(PermissionRecoveryService::class.java)

    fun start() {
        scope.launch {
            try {
                recover()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logger.error("Permission checkpoint recovery failed", error)
            }
        }
    }

    internal suspend fun recover() {
        workflowRepository.listRecoveryCandidates().forEach { checkpoint ->
            try {
                recover(checkpoint)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logger.error("Permission checkpoint recovery failed for {}", checkpoint.executionId, error)
            }
        }
    }

    private suspend fun recover(checkpoint: AgentExecutionCheckpointRecord) {
        if (!featureFlags.permissions) {
            recoverReady(checkpoint)
            return
        }
        if (
            checkpoint.phase == PermissionCheckpointPhase.RESUME_CLAIMED &&
            (checkpoint.leaseToken == null || checkpoint.leaseExpiresAt == null)
        ) {
            workflowRepository.failCheckpoint(
                executionId = checkpoint.executionId,
                errorCode = "permission_checkpoint_invalid",
                errorMessage = "Permission continuation checkpoint has no valid lease.",
            ).forEach { eventService.publishPersisted(it) }
            return
        }
        if (
            checkpoint.phase == PermissionCheckpointPhase.RESUME_CLAIMED &&
            checkpoint.leaseExpiresAt?.isAfter(Instant.now()) == true
        ) {
            scheduleExpiredClaim(checkpoint)
            return
        }
        recoverReady(checkpoint)
    }

    private suspend fun recoverReady(
        checkpoint: AgentExecutionCheckpointRecord,
    ) {
        if (!featureFlags.permissions) {
            workflowRepository.failCheckpoint(
                executionId = checkpoint.executionId,
                errorCode = "permissions_disabled",
                errorMessage = "Permission requests were disabled while the execution was waiting.",
            ).forEach { eventService.publishPersisted(it) }
            return
        }
        when (checkpoint.phase) {
            PermissionCheckpointPhase.WAITING_PERMISSION -> Unit
            PermissionCheckpointPhase.RESUME_QUEUED,
            PermissionCheckpointPhase.GRAPH_RESUMING
            -> requeueAndWake(checkpoint.executionId)

            PermissionCheckpointPhase.BATCH_READY,
            PermissionCheckpointPhase.RESUME_CLAIMED
            -> {
                val failed = workflowRepository.failUnknownOutcome(checkpoint.executionId)
                if (failed.isNotEmpty()) {
                    failed.forEach { eventService.publishPersisted(it) }
                } else {
                    requeueAndWake(checkpoint.executionId)
                }
            }
        }
    }

    private suspend fun requeueAndWake(executionId: UUID) {
        if (workflowRepository.requeueForRecovery(executionId)) {
            continuationDispatcher.wake(executionId)
        }
    }

    private fun scheduleExpiredClaim(
        checkpoint: AgentExecutionCheckpointRecord,
    ) {
        val expectedLeaseToken = checkpoint.leaseToken
        val expiresAt = checkNotNull(checkpoint.leaseExpiresAt)
        scope.launch {
            delay(Duration.between(Instant.now(), expiresAt).toMillis().coerceAtLeast(1L))
            val current = workflowRepository.getCheckpoint(checkpoint.executionId) ?: return@launch
            if (
                current.phase != PermissionCheckpointPhase.RESUME_CLAIMED ||
                current.leaseToken != expectedLeaseToken ||
                current.leaseExpiresAt?.isAfter(Instant.now()) == true
            ) {
                return@launch
            }
            try {
                recoverReady(current)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logger.error("Delayed permission recovery failed for {}", current.executionId, error)
            }
        }
    }
}
