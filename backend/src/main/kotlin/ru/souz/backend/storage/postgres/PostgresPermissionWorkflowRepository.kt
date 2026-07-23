package ru.souz.backend.storage.postgres

import com.fasterxml.jackson.module.kotlin.readValue
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import java.util.Locale
import javax.sql.DataSource
import ru.souz.agent.AgentId
import ru.souz.backend.agent.runtime.BackendAgentContextCheckpointV1
import ru.souz.backend.agent.session.AgentConversationState
import ru.souz.backend.events.model.AgentEvent
import ru.souz.backend.events.model.ExecutionCancelledPayload
import ru.souz.backend.events.model.ExecutionFailedPayload
import ru.souz.backend.events.model.PermissionRequestedPayload
import ru.souz.backend.events.model.PermissionResolvedPayload
import ru.souz.backend.events.model.ToolCallFailedPayload
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.permission.model.PermissionDecision
import ru.souz.backend.permission.model.PermissionRequest
import ru.souz.backend.permission.model.PermissionRequestStatus
import ru.souz.backend.permission.repository.AgentExecutionCheckpointRecord
import ru.souz.backend.permission.repository.AgentToolInvocationRecord
import ru.souz.backend.permission.repository.ClaimedPermissionContinuation
import ru.souz.backend.permission.repository.ParkPermissionCommand
import ru.souz.backend.permission.repository.PermissionCheckpointPhase
import ru.souz.backend.permission.repository.PermissionInvocationPhase
import ru.souz.backend.permission.repository.PermissionWorkflowRepository
import ru.souz.backend.permission.repository.PermissionWorkflowTransition
import ru.souz.backend.permission.repository.SaveToolBatchCommand
import ru.souz.backend.permission.repository.StoredPermissionDecisionResult

class PostgresPermissionWorkflowRepository(
    private val dataSource: DataSource,
) : PermissionWorkflowRepository {
    override suspend fun saveToolBatch(command: SaveToolBatchCommand): AgentExecutionCheckpointRecord =
        dataSource.write { connection ->
            connection.lockChat(command.userId, command.chatId)
            val execution = connection.requireExecutionForUpdate(command.executionId)
            require(execution.userId == command.userId && execution.chatId == command.chatId) {
                "Execution ownership does not match the tool checkpoint."
            }
            require(execution.status == AgentExecutionStatus.RUNNING) {
                "Execution must be running before a tool batch can be checkpointed."
            }

            val existing = connection.checkpointForUpdate(command.executionId)
            if (existing != null && existing.revision == command.revision) {
                require(existing.compatibilityKey == command.compatibilityKey) {
                    "Checkpoint compatibility mismatch for an existing revision."
                }
                return@write existing
            }
            require(existing == null || command.revision > existing.revision) {
                "Tool checkpoint revision must increase."
            }

            connection.prepareStatement(
                """
                insert into agent_execution_checkpoints(
                  execution_id, user_id, chat_id, schema_version, revision, phase,
                  context_json, batch_json, next_ordinal, base_state_row_version,
                  compatibility_key, lease_token, lease_expires_at, created_at,
                  updated_at, row_version
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, null, null, ?, ?, 0)
                on conflict (execution_id) do update
                set schema_version = excluded.schema_version,
                    revision = excluded.revision,
                    phase = excluded.phase,
                    context_json = excluded.context_json,
                    batch_json = excluded.batch_json,
                    next_ordinal = excluded.next_ordinal,
                    base_state_row_version = excluded.base_state_row_version,
                    compatibility_key = excluded.compatibility_key,
                    lease_token = null,
                    lease_expires_at = null,
                    updated_at = excluded.updated_at,
                    row_version = agent_execution_checkpoints.row_version + 1
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, command.executionId)
                statement.setString(2, command.userId)
                statement.setObject(3, command.chatId)
                statement.setInt(4, command.schemaVersion)
                statement.setLong(5, command.revision)
                statement.setString(6, PermissionCheckpointPhase.BATCH_READY.value)
                statement.setJson(7, command.contextJson)
                statement.setJson(8, command.batchJson)
                statement.setInt(9, command.nextOrdinal)
                statement.setLong(10, command.baseStateRowVersion)
                statement.setString(11, command.compatibilityKey)
                statement.setInstant(12, command.now)
                statement.setInstant(13, command.now)
                statement.executeUpdate()
            }

            connection.prepareStatement(
                """
                insert into agent_tool_invocations(
                  execution_id, invocation_id, user_id, chat_id, batch_revision,
                  ordinal, provider_call_id, tool_name, arguments_json,
                  arguments_hash, tool_definition_hash, phase, result_message_json,
                  error_code, started_at, finished_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, null, null, null, null, ?)
                """.trimIndent()
            ).use { statement ->
                command.invocations.sortedBy { it.ordinal }.forEach { invocation ->
                    statement.setObject(1, command.executionId)
                    statement.setObject(2, invocation.invocationId)
                    statement.setString(3, command.userId)
                    statement.setObject(4, command.chatId)
                    statement.setLong(5, command.revision)
                    statement.setInt(6, invocation.ordinal)
                    statement.setString(7, invocation.providerCallId)
                    statement.setString(8, invocation.toolName)
                    statement.setJson(9, invocation.argumentsJson)
                    statement.setString(10, invocation.argumentsHash)
                    statement.setString(11, invocation.toolDefinitionHash)
                    statement.setString(12, PermissionInvocationPhase.PLANNED.value)
                    statement.setInstant(13, command.now)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
            connection.requireCheckpoint(command.executionId)
        }

    override suspend fun beginInvocation(
        executionId: UUID,
        invocationId: UUID,
        now: Instant,
    ): AgentToolInvocationRecord = dataSource.write { connection ->
        val checkpoint = connection.requireCheckpointForUpdate(executionId)
        val invocation = connection.requireInvocationForUpdate(executionId, invocationId)
        if (invocation.phase == PermissionInvocationPhase.INVOKING) {
            return@write invocation
        }
        require(invocation.batchRevision == checkpoint.revision && invocation.ordinal == checkpoint.nextOrdinal) {
            "Tool invocation does not match the checkpoint cursor."
        }
        require(
            checkpoint.phase == PermissionCheckpointPhase.BATCH_READY ||
                checkpoint.phase == PermissionCheckpointPhase.RESUME_CLAIMED
        ) { "Checkpoint cannot begin an invocation from phase ${checkpoint.phase.value}." }
        require(invocation.phase == PermissionInvocationPhase.PLANNED) {
            "Only a planned tool invocation may begin."
        }
        connection.prepareStatement(
            """
            update agent_tool_invocations
            set phase = ?, started_at = coalesce(started_at, ?), updated_at = ?
            where execution_id = ? and invocation_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, PermissionInvocationPhase.INVOKING.value)
            statement.setInstant(2, now)
            statement.setInstant(3, now)
            statement.setObject(4, executionId)
            statement.setObject(5, invocationId)
            statement.executeUpdate()
        }
        connection.requireInvocation(executionId, invocationId)
    }

    override suspend fun park(command: ParkPermissionCommand): PermissionWorkflowTransition =
        dataSource.write { connection ->
            connection.lockChat(command.userId, command.chatId)
            val execution = connection.requireExecutionForUpdate(command.executionId)
            require(execution.userId == command.userId && execution.chatId == command.chatId) {
                "Execution ownership does not match the permission request."
            }
            val checkpoint = connection.requireCheckpointForUpdate(command.executionId)
            val invocation = connection.requireInvocationForUpdate(command.executionId, command.invocationId)
            val existing = connection.permissionForInvocation(command.executionId, command.invocationId, forUpdate = true)
            if (existing != null) {
                require(
                    existing.toolName == command.toolName &&
                        existing.toolCallId == command.toolCallId &&
                        existing.description == command.description &&
                        existing.displayParams == command.displayParams
                ) { "An existing permission request does not match the replayed prompt." }
                return@write PermissionWorkflowTransition(existing, execution, null)
            }
            require(execution.status == AgentExecutionStatus.RUNNING && !execution.cancelRequested) {
                "Execution is not running."
            }
            require(checkpoint.phase == PermissionCheckpointPhase.BATCH_READY) {
                "Execution checkpoint is not ready to wait for permission."
            }
            require(invocation.phase == PermissionInvocationPhase.INVOKING) {
                "Tool invocation is not at a permission-safe boundary."
            }

            val request = PermissionRequest(
                id = UUID.randomUUID(),
                userId = command.userId,
                chatId = command.chatId,
                executionId = command.executionId,
                invocationId = command.invocationId,
                toolName = command.toolName,
                toolCallId = command.toolCallId,
                description = command.description,
                displayParams = command.displayParams,
                status = PermissionRequestStatus.PENDING,
                createdAt = command.now,
                resolvedAt = null,
            )
            connection.insertPermission(request, command.promptHash)
            connection.updateInvocationPhase(
                executionId = command.executionId,
                invocationId = command.invocationId,
                phase = PermissionInvocationPhase.WAITING_PERMISSION,
                now = command.now,
            )
            connection.updateCheckpointPhase(
                executionId = command.executionId,
                phase = PermissionCheckpointPhase.WAITING_PERMISSION,
                now = command.now,
            )
            val waitingExecution = connection.updateExecutionStatus(
                execution = execution,
                status = AgentExecutionStatus.WAITING_PERMISSION,
                now = command.now,
                usage = command.usage,
            )
            val event = connection.appendAgentEvent(
                userId = command.userId,
                chatId = command.chatId,
                executionId = command.executionId,
                type = AgentEventType.PERMISSION_REQUESTED,
                payload = PermissionRequestedPayload(
                    permissionRequestId = request.id,
                    invocationId = request.invocationId,
                    toolName = request.toolName,
                    toolCallId = request.toolCallId,
                    description = request.description,
                    displayParams = request.displayParams,
                ),
                createdAt = command.now,
            )
            PermissionWorkflowTransition(request, waitingExecution, event)
        }

    override suspend fun listPending(userId: String, chatId: UUID): List<PermissionRequest> =
        dataSource.read { connection ->
            connection.prepareStatement(
                """
                select * from permission_requests
                where user_id = ? and chat_id = ? and status = 'pending'
                order by created_at asc, id asc
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, userId)
                statement.setObject(2, chatId)
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) add(resultSet.toPermissionRequest())
                    }
                }
            }
        }

    override suspend fun getOwned(userId: String, permissionRequestId: UUID): PermissionRequest? =
        dataSource.read { connection ->
            connection.prepareStatement(
                "select * from permission_requests where user_id = ? and id = ?"
            ).use { statement ->
                statement.setString(1, userId)
                statement.setObject(2, permissionRequestId)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) resultSet.toPermissionRequest() else null
                }
            }
        }

    override suspend fun decide(
        userId: String,
        permissionRequestId: UUID,
        decision: PermissionDecision,
        now: Instant,
    ): StoredPermissionDecisionResult {
        val location = dataSource.read { connection ->
            connection.prepareStatement(
                "select chat_id, execution_id from permission_requests where user_id = ? and id = ?"
            ).use { statement ->
                statement.setString(1, userId)
                statement.setObject(2, permissionRequestId)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) {
                        resultSet.getObject("chat_id", UUID::class.java) to
                            resultSet.getObject("execution_id", UUID::class.java)
                    } else {
                        null
                    }
                }
            }
        } ?: return StoredPermissionDecisionResult.NotFound

        return dataSource.write { connection ->
            val (chatId, executionId) = location
            connection.lockChat(userId, chatId)
            val execution = connection.requireExecutionForUpdate(executionId)
            val request = connection.permissionById(permissionRequestId, forUpdate = true)
                ?.takeIf { it.userId == userId }
                ?: return@write StoredPermissionDecisionResult.NotFound
            val targetStatus = when (decision) {
                PermissionDecision.GRANT -> PermissionRequestStatus.GRANTED
                PermissionDecision.DENY -> PermissionRequestStatus.DENIED
            }
            if (request.status == targetStatus) {
                return@write StoredPermissionDecisionResult.Idempotent(request, execution)
            }
            if (request.status != PermissionRequestStatus.PENDING) {
                return@write StoredPermissionDecisionResult.Conflict
            }
            if (execution.status != AgentExecutionStatus.WAITING_PERMISSION || execution.cancelRequested) {
                return@write StoredPermissionDecisionResult.Conflict
            }
            val checkpoint = connection.checkpointForUpdate(executionId)
                ?: return@write StoredPermissionDecisionResult.Conflict
            if (checkpoint.phase != PermissionCheckpointPhase.WAITING_PERMISSION) {
                return@write StoredPermissionDecisionResult.Conflict
            }

            connection.prepareStatement(
                "update permission_requests set status = ?, decided_at = ? where id = ? and status = 'pending'"
            ).use { statement ->
                statement.setString(1, targetStatus.value)
                statement.setInstant(2, now)
                statement.setObject(3, permissionRequestId)
                if (statement.executeUpdate() != 1) {
                    return@write StoredPermissionDecisionResult.Conflict
                }
            }
            connection.updateCheckpointPhase(
                executionId = executionId,
                phase = PermissionCheckpointPhase.RESUME_QUEUED,
                now = now,
                clearLease = true,
            )
            val queuedExecution = connection.updateExecutionStatus(
                execution = execution,
                status = AgentExecutionStatus.QUEUED,
                now = now,
            )
            val resolved = request.copy(status = targetStatus, resolvedAt = now)
            val event = connection.appendAgentEvent(
                userId = userId,
                chatId = chatId,
                executionId = executionId,
                type = AgentEventType.PERMISSION_RESOLVED,
                payload = PermissionResolvedPayload(
                    permissionRequestId = resolved.id,
                    invocationId = resolved.invocationId,
                    toolName = resolved.toolName,
                    toolCallId = resolved.toolCallId,
                    status = resolved.status.value,
                ),
                createdAt = now,
            )
            StoredPermissionDecisionResult.Updated(
                PermissionWorkflowTransition(resolved, queuedExecution, event)
            )
        }
    }

    override suspend fun claimReady(
        executionId: UUID,
        leaseToken: UUID,
        leaseExpiresAt: Instant,
        now: Instant,
    ): ClaimedPermissionContinuation? {
        val location = dataSource.read { connection ->
            connection.prepareStatement(
                "select user_id, chat_id from agent_execution_checkpoints where execution_id = ?"
            ).use { statement ->
                statement.setObject(1, executionId)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) {
                        resultSet.getString("user_id") to resultSet.getObject("chat_id", UUID::class.java)
                    } else null
                }
            }
        } ?: return null
        return dataSource.write { connection ->
            val (userId, chatId) = location
            connection.lockChat(userId, chatId)
            val execution = connection.requireExecutionForUpdate(executionId)
            val checkpoint = connection.requireCheckpointForUpdate(executionId)
            val claimable = checkpoint.phase == PermissionCheckpointPhase.RESUME_QUEUED ||
                checkpoint.phase == PermissionCheckpointPhase.GRAPH_RESUMING ||
                checkpoint.phase == PermissionCheckpointPhase.BATCH_READY ||
                (checkpoint.phase == PermissionCheckpointPhase.RESUME_CLAIMED &&
                    checkpoint.leaseExpiresAt?.let { !it.isAfter(now) } == true)
            // A RUNNING execution owns its continuation even while a result/checkpoint update is
            // between transactions. Only a decision or explicit startup recovery may queue work.
            val resumableExecution = execution.status == AgentExecutionStatus.QUEUED
            if (!claimable || !resumableExecution || execution.cancelRequested) return@write null

            val currentInvocation = connection.prepareStatement(
                """
                select * from agent_tool_invocations
                where execution_id = ? and batch_revision = ? and ordinal = ?
                for update
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, executionId)
                statement.setLong(2, checkpoint.revision)
                statement.setInt(3, checkpoint.nextOrdinal)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) resultSet.toPermissionInvocation() else null
                }
            }
            if (
                currentInvocation?.phase == PermissionInvocationPhase.INVOKING ||
                currentInvocation?.phase == PermissionInvocationPhase.EXECUTING
            ) return@write null

            val invocation = currentInvocation ?: connection.prepareStatement(
                """
                select * from agent_tool_invocations
                where execution_id = ? and batch_revision = ? and ordinal < ?
                  and phase = 'result_stored'
                order by ordinal desc limit 1 for update
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, executionId)
                statement.setLong(2, checkpoint.revision)
                statement.setInt(3, checkpoint.nextOrdinal)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) resultSet.toPermissionInvocation() else null
                }
            } ?: return@write null

            val permission = connection.permissionForInvocation(
                executionId = executionId,
                invocationId = invocation.invocationId,
                forUpdate = true,
            )?.takeIf { request ->
                request.status == PermissionRequestStatus.GRANTED ||
                    request.status == PermissionRequestStatus.DENIED
            }
            val resultAlreadyStored = invocation.phase == PermissionInvocationPhase.RESULT_STORED
            val permissionResume = invocation.phase == PermissionInvocationPhase.WAITING_PERMISSION ||
                invocation.phase == PermissionInvocationPhase.RESUME_CLAIMED
            val safePlainResume = invocation.phase == PermissionInvocationPhase.PLANNED
            if (!resultAlreadyStored && !safePlainResume && !permissionResume) return@write null
            if (permissionResume && permission == null) return@write null

            connection.prepareStatement(
                """
                update agent_execution_checkpoints
                set phase = ?, lease_token = ?, lease_expires_at = ?, updated_at = ?, row_version = row_version + 1
                where execution_id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, PermissionCheckpointPhase.RESUME_CLAIMED.value)
                statement.setObject(2, leaseToken)
                statement.setInstant(3, leaseExpiresAt)
                statement.setInstant(4, now)
                statement.setObject(5, executionId)
                statement.executeUpdate()
            }
            if (invocation.phase == PermissionInvocationPhase.WAITING_PERMISSION) {
                connection.updateInvocationPhase(
                    executionId = executionId,
                    invocationId = invocation.invocationId,
                    phase = PermissionInvocationPhase.RESUME_CLAIMED,
                    now = now,
                )
            }
            val runningExecution = connection.updateExecutionStatus(
                execution = execution,
                status = AgentExecutionStatus.RUNNING,
                now = now,
            )
            ClaimedPermissionContinuation(
                checkpoint = connection.requireCheckpoint(executionId),
                invocation = connection.requireInvocation(executionId, invocation.invocationId),
                permissionRequest = permission,
                execution = runningExecution,
            )
        }
    }

    override suspend fun markExecuting(
        permissionRequestId: UUID,
        leaseToken: UUID,
        now: Instant,
    ): Boolean {
        val location = dataSource.read { connection ->
            connection.prepareStatement(
                "select user_id, chat_id, execution_id from permission_requests where id = ? and status = 'granted'"
            ).use { statement ->
                statement.setObject(1, permissionRequestId)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) {
                        Triple(
                            resultSet.getString("user_id"),
                            resultSet.getObject("chat_id", UUID::class.java),
                            resultSet.getObject("execution_id", UUID::class.java),
                        )
                    } else null
                }
            }
        } ?: return false
        return dataSource.write { connection ->
            val (userId, chatId, executionId) = location
            connection.lockChat(userId, chatId)
            val execution = connection.requireExecutionForUpdate(executionId)
            val checkpoint = connection.requireCheckpointForUpdate(executionId)
            if (
                checkpoint.phase != PermissionCheckpointPhase.RESUME_CLAIMED ||
                checkpoint.leaseToken != leaseToken ||
                execution.status != AgentExecutionStatus.RUNNING ||
                execution.cancelRequested
            ) return@write false
            val permission = connection.permissionById(permissionRequestId, forUpdate = true)
                ?: return@write false
            if (permission.status != PermissionRequestStatus.GRANTED) return@write false
            val invocation = connection.requireInvocationForUpdate(executionId, permission.invocationId)
            if (invocation.phase == PermissionInvocationPhase.EXECUTING) return@write true
            if (invocation.phase != PermissionInvocationPhase.RESUME_CLAIMED) return@write false
            connection.updateInvocationPhase(
                executionId = executionId,
                invocationId = permission.invocationId,
                phase = PermissionInvocationPhase.EXECUTING,
                now = now,
            )
            true
        }
    }

    override suspend fun isClaimActive(
        executionId: UUID,
        leaseToken: UUID,
        now: Instant,
    ): Boolean = dataSource.read { connection ->
        connection.prepareStatement(
            """
            select 1
            from agent_execution_checkpoints checkpoint
            join agent_executions execution on execution.id = checkpoint.execution_id
            where checkpoint.execution_id = ?
              and checkpoint.phase = 'resume_claimed'
              and checkpoint.lease_token = ?
              and checkpoint.lease_expires_at > ?
              and execution.status = 'running'
              and execution.cancel_requested = false
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, executionId)
            statement.setObject(2, leaseToken)
            statement.setInstant(3, now)
            statement.executeQuery().use { resultSet -> resultSet.next() }
        }
    }

    override suspend fun storeToolResult(
        executionId: UUID,
        invocationId: UUID,
        resultMessageJson: String,
        contextJson: String,
        nextOrdinal: Int,
        now: Instant,
    ): AgentExecutionCheckpointRecord = dataSource.write { connection ->
        val checkpoint = connection.requireCheckpointForUpdate(executionId)
        val invocation = connection.requireInvocationForUpdate(executionId, invocationId)
        if (invocation.phase == PermissionInvocationPhase.RESULT_STORED) {
            require(invocation.resultMessageJson == resultMessageJson) {
                "A different result was already stored for this invocation."
            }
            require(checkpoint.nextOrdinal == nextOrdinal) {
                "The stored result does not match the checkpoint cursor."
            }
            return@write checkpoint
        }
        require(
            invocation.batchRevision == checkpoint.revision &&
                invocation.ordinal == checkpoint.nextOrdinal &&
                nextOrdinal == invocation.ordinal + 1
        ) { "Tool result does not advance the current checkpoint invocation." }
        require(
            invocation.phase == PermissionInvocationPhase.INVOKING ||
                invocation.phase == PermissionInvocationPhase.RESUME_CLAIMED ||
                invocation.phase == PermissionInvocationPhase.EXECUTING
        ) { "Tool invocation cannot store a result from phase ${invocation.phase.value}." }
        connection.prepareStatement(
            """
            update agent_tool_invocations
            set phase = ?, result_message_json = ?, error_code = null,
                finished_at = ?, updated_at = ?
            where execution_id = ? and invocation_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, PermissionInvocationPhase.RESULT_STORED.value)
            statement.setJson(2, resultMessageJson)
            statement.setInstant(3, now)
            statement.setInstant(4, now)
            statement.setObject(5, executionId)
            statement.setObject(6, invocationId)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            update agent_execution_checkpoints
            set phase = ?, context_json = ?, next_ordinal = ?, lease_token = null,
                lease_expires_at = null, updated_at = ?, row_version = row_version + 1
            where execution_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, PermissionCheckpointPhase.BATCH_READY.value)
            statement.setJson(2, contextJson)
            statement.setInt(3, nextOrdinal)
            statement.setInstant(4, now)
            statement.setObject(5, executionId)
            statement.executeUpdate()
        }
        connection.requireCheckpoint(executionId)
    }

    override suspend fun markGraphResuming(
        executionId: UUID,
        contextJson: String,
        now: Instant,
    ): AgentExecutionCheckpointRecord = dataSource.write { connection ->
        connection.requireCheckpointForUpdate(executionId)
        connection.prepareStatement(
            """
            update agent_execution_checkpoints
            set phase = ?, context_json = ?, lease_token = null,
                lease_expires_at = null, updated_at = ?, row_version = row_version + 1
            where execution_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, PermissionCheckpointPhase.GRAPH_RESUMING.value)
            statement.setJson(2, contextJson)
            statement.setInstant(3, now)
            statement.setObject(4, executionId)
            statement.executeUpdate()
        }
        connection.requireCheckpoint(executionId)
    }

    override suspend fun getCheckpoint(executionId: UUID): AgentExecutionCheckpointRecord? =
        dataSource.read { connection -> connection.checkpoint(executionId) }

    override suspend fun listRecoveryCandidates(now: Instant): List<AgentExecutionCheckpointRecord> =
        dataSource.write { connection ->
            connection.prepareStatement(
                """
                update agent_tool_invocations invocation
                set phase = 'failed',
                    error_code = coalesce(execution.error_code, 'execution_terminated'),
                    finished_at = coalesce(invocation.finished_at, now()),
                    updated_at = now()
                from agent_executions execution
                where execution.id = invocation.execution_id
                  and execution.status in ('cancelled', 'completed', 'failed')
                  and invocation.phase not in ('result_stored', 'failed')
                  and exists (
                    select 1 from agent_execution_checkpoints checkpoint
                    where checkpoint.execution_id = invocation.execution_id
                  )
                """.trimIndent()
            ).use { statement -> statement.executeUpdate() }
            connection.prepareStatement(
                """
                delete from agent_execution_checkpoints checkpoint
                using agent_executions execution
                where execution.id = checkpoint.execution_id
                  and execution.status in ('cancelled', 'completed', 'failed')
                """.trimIndent()
            ).use { statement -> statement.executeUpdate() }
            connection.prepareStatement(
                """
                select * from agent_execution_checkpoints
                where phase in (
                  'waiting_permission', 'resume_queued', 'resume_claimed',
                  'batch_ready', 'graph_resuming'
                )
                order by updated_at asc, execution_id asc
                """.trimIndent()
            ).use { statement ->
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) add(resultSet.toPermissionCheckpoint())
                    }
                }
            }
        }

    override suspend fun requeueForRecovery(
        executionId: UUID,
        now: Instant,
    ): Boolean {
        val location = dataSource.read { connection ->
            connection.prepareStatement(
                "select user_id, chat_id from agent_execution_checkpoints where execution_id = ?"
            ).use { statement ->
                statement.setObject(1, executionId)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) {
                        resultSet.getString(1) to resultSet.getObject(2, UUID::class.java)
                    } else null
                }
            }
        } ?: return false
        return dataSource.write { connection ->
            val (userId, chatId) = location
            connection.lockChat(userId, chatId)
            val execution = connection.requireExecutionForUpdate(executionId)
            val checkpoint = connection.checkpointForUpdate(executionId) ?: return@write false
            if (execution.status.isTerminal()) {
                connection.deletePermissionCheckpoint(executionId)
                return@write false
            }

            val hasAmbiguousInvocation = connection.prepareStatement(
                """
                select 1 from agent_tool_invocations
                where execution_id = ? and phase in ('invoking', 'executing')
                limit 1
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, executionId)
                statement.executeQuery().use(ResultSet::next)
            }
            if (execution.status == AgentExecutionStatus.WAITING_OPTION) {
                // ChoiceRequested makes the option authoritative before the finalizer saves the
                // resumed session. Reconcile the durable checkpoint first so a crash in that
                // window neither replays the option nor loses the history that produced it.
                check(!hasAmbiguousInvocation) {
                    "A waiting-option handoff still has an invocation with an unknown outcome."
                }
                connection.reconcileWaitingOptionState(execution, checkpoint, now)
                connection.deletePermissionCheckpoint(executionId)
                return@write false
            }
            if (
                execution.cancelRequested ||
                checkpoint.phase == PermissionCheckpointPhase.WAITING_PERMISSION
            ) return@write false
            if (hasAmbiguousInvocation) return@write false

            connection.updateCheckpointPhase(
                executionId = executionId,
                phase = PermissionCheckpointPhase.RESUME_QUEUED,
                now = now,
                clearLease = true,
            )
            connection.updateExecutionStatus(
                execution = execution,
                status = AgentExecutionStatus.QUEUED,
                now = now,
            )
            true
        }
    }

    override suspend fun cancelPendingForExecution(
        userId: String,
        chatId: UUID,
        executionId: UUID,
        now: Instant,
    ): List<AgentEvent> = dataSource.write { connection ->
        connection.lockChat(userId, chatId)
        val execution = connection.requireExecutionForUpdate(executionId)
        require(execution.userId == userId && execution.chatId == chatId) {
            "Execution ownership does not match cancellation."
        }
        if (execution.status.isTerminal()) {
            connection.deletePermissionCheckpoint(executionId)
            return@write emptyList()
        }
        val request = connection.prepareStatement(
            "select * from permission_requests where execution_id = ? and status = 'pending' for update"
        ).use { statement ->
            statement.setObject(1, executionId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toPermissionRequest() else null
            }
        } ?: return@write emptyList()

        connection.prepareStatement(
            "update permission_requests set status = 'cancelled', decided_at = ? where id = ?"
        ).use { statement ->
            statement.setInstant(1, now)
            statement.setObject(2, request.id)
            statement.executeUpdate()
        }
        val invocation = connection.requireInvocationForUpdate(executionId, request.invocationId)
        connection.prepareStatement(
            """
            update agent_tool_invocations
            set phase = 'failed', error_code = 'agent_execution_cancelled',
                finished_at = ?, updated_at = ?
            where execution_id = ? and invocation_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setInstant(1, now)
            statement.setInstant(2, now)
            statement.setObject(3, executionId)
            statement.setObject(4, request.invocationId)
            statement.executeUpdate()
        }
        connection.updateExecutionTerminal(
            execution = execution,
            status = AgentExecutionStatus.CANCELLED,
            errorCode = "agent_execution_cancelled",
            errorMessage = "Agent execution was cancelled.",
            now = now,
        )
        connection.deletePermissionCheckpoint(executionId)
        val resolved = connection.appendAgentEvent(
            userId = userId,
            chatId = chatId,
            executionId = executionId,
            type = AgentEventType.PERMISSION_RESOLVED,
            payload = PermissionResolvedPayload(
                permissionRequestId = request.id,
                invocationId = request.invocationId,
                toolName = request.toolName,
                toolCallId = request.toolCallId,
                status = PermissionRequestStatus.CANCELLED.value,
            ),
            createdAt = now,
        )
        buildList {
            add(resolved)
            connection.failRunningToolCall(
                invocation = invocation,
                error = "Tool call cancelled while awaiting permission.",
                emitEvent = execution.metadata[TOOL_EVENTS_METADATA_KEY] == "true",
                now = now,
            )?.let(::add)
            add(
                connection.appendAgentEvent(
                    userId = userId,
                    chatId = chatId,
                    executionId = executionId,
                    type = AgentEventType.EXECUTION_CANCELLED,
                    payload = ExecutionCancelledPayload(
                        executionId = executionId,
                        assistantMessageId = execution.assistantMessageId,
                    ),
                    createdAt = now,
                )
            )
        }
    }

    override suspend fun failUnknownOutcome(executionId: UUID, now: Instant): List<AgentEvent> {
        val location = dataSource.read { connection ->
            connection.prepareStatement(
                "select user_id, chat_id from agent_execution_checkpoints where execution_id = ?"
            ).use { statement ->
                statement.setObject(1, executionId)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) {
                        resultSet.getString(1) to resultSet.getObject(2, UUID::class.java)
                    } else null
                }
            }
        } ?: return emptyList()
        return dataSource.write { connection ->
            val (userId, chatId) = location
            connection.lockChat(userId, chatId)
            val execution = connection.requireExecutionForUpdate(executionId)
            if (execution.status.isTerminal()) {
                connection.deletePermissionCheckpoint(executionId)
                return@write emptyList()
            }
            val ambiguous = connection.prepareStatement(
                """
                select * from agent_tool_invocations
                where execution_id = ? and phase in ('invoking', 'executing')
                order by ordinal asc limit 1 for update
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, executionId)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) resultSet.toPermissionInvocation() else null
                }
            } ?: return@write emptyList()
            connection.prepareStatement(
                """
                update agent_tool_invocations
                set phase = 'failed', error_code = 'tool_outcome_unknown',
                    finished_at = ?, updated_at = ?
                where execution_id = ? and invocation_id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setInstant(1, now)
                statement.setInstant(2, now)
                statement.setObject(3, executionId)
                statement.setObject(4, ambiguous.invocationId)
                statement.executeUpdate()
            }
            connection.updateExecutionTerminal(
                execution = execution,
                status = AgentExecutionStatus.FAILED,
                errorCode = "tool_outcome_unknown",
                errorMessage = "The backend restarted while an authorized tool outcome was unknown.",
                now = now,
            )
            connection.deletePermissionCheckpoint(executionId)
            buildList {
                connection.failRunningToolCall(
                    invocation = ambiguous,
                    error = "Tool outcome is unknown after backend recovery.",
                    emitEvent = execution.metadata[TOOL_EVENTS_METADATA_KEY] == "true",
                    now = now,
                )?.let(::add)
                add(
                    connection.appendAgentEvent(
                        userId = userId,
                        chatId = chatId,
                        executionId = executionId,
                        type = AgentEventType.EXECUTION_FAILED,
                        payload = ExecutionFailedPayload(
                            executionId = executionId,
                            assistantMessageId = execution.assistantMessageId,
                            errorCode = "tool_outcome_unknown",
                            errorMessage = "The backend restarted while an authorized tool outcome was unknown.",
                        ),
                        createdAt = now,
                    )
                )
            }
        }
    }

    override suspend fun failCheckpoint(
        executionId: UUID,
        errorCode: String,
        errorMessage: String,
        now: Instant,
    ): List<AgentEvent> {
        val location = dataSource.read { connection ->
            connection.prepareStatement(
                "select user_id, chat_id from agent_execution_checkpoints where execution_id = ?"
            ).use { statement ->
                statement.setObject(1, executionId)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) {
                        resultSet.getString(1) to resultSet.getObject(2, UUID::class.java)
                    } else null
                }
            }
        } ?: return emptyList()
        return dataSource.write { connection ->
            val (userId, chatId) = location
            connection.lockChat(userId, chatId)
            val execution = connection.requireExecutionForUpdate(executionId)
            if (execution.status.isTerminal()) {
                connection.deletePermissionCheckpoint(executionId)
                return@write emptyList()
            }
            val events = mutableListOf<AgentEvent>()
            val pending = connection.prepareStatement(
                "select * from permission_requests where execution_id = ? and status = 'pending' for update"
            ).use { statement ->
                statement.setObject(1, executionId)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) resultSet.toPermissionRequest() else null
                }
            }
            if (pending != null) {
                val invocation = connection.requireInvocationForUpdate(executionId, pending.invocationId)
                connection.prepareStatement(
                    "update permission_requests set status = 'cancelled', decided_at = ? where id = ?"
                ).use { statement ->
                    statement.setInstant(1, now)
                    statement.setObject(2, pending.id)
                    statement.executeUpdate()
                }
                events += connection.appendAgentEvent(
                    userId = userId,
                    chatId = chatId,
                    executionId = executionId,
                    type = AgentEventType.PERMISSION_RESOLVED,
                    payload = PermissionResolvedPayload(
                        permissionRequestId = pending.id,
                        invocationId = pending.invocationId,
                        toolName = pending.toolName,
                        toolCallId = pending.toolCallId,
                        status = PermissionRequestStatus.CANCELLED.value,
                    ),
                    createdAt = now,
                )
                connection.failRunningToolCall(
                    invocation = invocation,
                    error = errorMessage,
                    emitEvent = execution.metadata[TOOL_EVENTS_METADATA_KEY] == "true",
                    now = now,
                )?.let(events::add)
            }
            connection.updateExecutionTerminal(
                execution = execution,
                status = AgentExecutionStatus.FAILED,
                errorCode = errorCode,
                errorMessage = errorMessage,
                now = now,
            )
            connection.deletePermissionCheckpoint(executionId)
            events += connection.appendAgentEvent(
                userId = userId,
                chatId = chatId,
                executionId = executionId,
                type = AgentEventType.EXECUTION_FAILED,
                payload = ExecutionFailedPayload(
                    executionId = executionId,
                    assistantMessageId = execution.assistantMessageId,
                    errorCode = errorCode,
                    errorMessage = errorMessage,
                ),
                createdAt = now,
            )
            events
        }
    }

    override suspend fun deleteCheckpoint(executionId: UUID) {
        dataSource.write { connection ->
            connection.deletePermissionCheckpoint(executionId)
        }
    }
}

private fun Connection.reconcileWaitingOptionState(
    execution: AgentExecution,
    checkpoint: AgentExecutionCheckpointRecord,
    now: Instant,
) {
    val current = prepareStatement(
        "select * from agent_conversation_state where user_id = ? and chat_id = ? for update"
    ).use { statement ->
        statement.setString(1, execution.userId)
        statement.setObject(2, execution.chatId)
        statement.executeQuery().use { resultSet ->
            if (resultSet.next()) resultSet.toState() else null
        }
    }
    if (current != null) {
        if (
            current.rowVersion > checkpoint.baseStateRowVersion ||
            (
                current.rowVersion == checkpoint.baseStateRowVersion &&
                    current.updatedAt.isAfter(checkpoint.updatedAt)
            )
        ) {
            return
        }
    }
    check(
        current?.rowVersion == checkpoint.baseStateRowVersion ||
            (current == null && checkpoint.baseStateRowVersion == 0L)
    ) { "Conversation state no longer matches the waiting-option checkpoint base version." }

    val snapshot = postgresStorageMapper.readValue<BackendAgentContextCheckpointV1>(checkpoint.contextJson)
    check(snapshot.schemaVersion == BackendAgentContextCheckpointV1.SCHEMA_VERSION) {
        "Unsupported permission checkpoint schema ${snapshot.schemaVersion}."
    }
    val recovered = AgentConversationState(
        userId = execution.userId,
        chatId = execution.chatId,
        schemaVersion = snapshot.schemaVersion,
        activeAgentId = AgentId.fromStorageValue(snapshot.activeAgentId),
        history = snapshot.history,
        temperature = snapshot.temperature,
        locale = execution.metadata[EXECUTION_LOCALE_METADATA_KEY]
            ?.let(Locale::forLanguageTag)
            ?.takeIf { it.language.isNotBlank() }
            ?: current?.locale
            ?: DEFAULT_RECOVERY_LOCALE,
        timeZone = execution.metadata[EXECUTION_TIME_ZONE_METADATA_KEY]
            ?.let { value -> runCatching { ZoneId.of(value) }.getOrNull() }
            ?: current?.timeZone
            ?: ZoneId.systemDefault(),
        basedOnMessageSeq = current?.basedOnMessageSeq ?: 0L,
        updatedAt = now,
        rowVersion = checkpoint.baseStateRowVersion,
    )
    if (current == null) {
        prepareStatement(
            """
            insert into agent_conversation_state(
              user_id, chat_id, context_json, based_on_message_seq, updated_at, row_version
            ) values (?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, recovered.userId)
            statement.setObject(2, recovered.chatId)
            statement.setJson(3, recovered.toContextJson())
            statement.setLong(4, recovered.basedOnMessageSeq)
            statement.setInstant(5, recovered.updatedAt)
            statement.setLong(6, recovered.rowVersion)
            check(statement.executeUpdate() == 1) { "Waiting-option state insert failed." }
        }
    } else {
        prepareStatement(
            """
            update agent_conversation_state
            set context_json = ?, based_on_message_seq = ?, updated_at = ?,
                row_version = row_version + 1
            where user_id = ? and chat_id = ? and row_version = ?
            """.trimIndent()
        ).use { statement ->
            statement.setJson(1, recovered.toContextJson())
            statement.setLong(2, recovered.basedOnMessageSeq)
            statement.setInstant(3, recovered.updatedAt)
            statement.setString(4, recovered.userId)
            statement.setObject(5, recovered.chatId)
            statement.setLong(6, checkpoint.baseStateRowVersion)
            check(statement.executeUpdate() == 1) { "Waiting-option state update lost its version fence." }
        }
    }
}

private const val EXECUTION_LOCALE_METADATA_KEY = "locale"
private const val EXECUTION_TIME_ZONE_METADATA_KEY = "timeZone"
private val DEFAULT_RECOVERY_LOCALE: Locale = Locale.forLanguageTag("ru-RU")

private fun AgentExecutionStatus.isTerminal(): Boolean =
    this == AgentExecutionStatus.CANCELLED ||
        this == AgentExecutionStatus.COMPLETED ||
        this == AgentExecutionStatus.FAILED

private fun Connection.failRunningToolCall(
    invocation: AgentToolInvocationRecord,
    error: String,
    emitEvent: Boolean,
    now: Instant,
): AgentEvent? {
    val toolCallId = invocation.providerCallId ?: invocation.invocationId.toString()
    val durationMs = prepareStatement(
        """
        update tool_calls
        set status = 'failed', error = ?, finished_at = ?,
            duration_ms = greatest(
              0,
              (extract(epoch from (?::timestamptz - started_at)) * 1000)::bigint
            )
        where user_id = ? and chat_id = ? and execution_id = ?
          and tool_call_id = ? and status = 'running'
        returning duration_ms
        """.trimIndent()
    ).use { statement ->
        statement.setString(1, error)
        statement.setInstant(2, now)
        statement.setInstant(3, now)
        statement.setString(4, invocation.userId)
        statement.setObject(5, invocation.chatId)
        statement.setObject(6, invocation.executionId)
        statement.setString(7, toolCallId)
        statement.executeQuery().use { resultSet ->
            if (resultSet.next()) resultSet.getLong(1) else null
        }
    } ?: return null
    if (!emitEvent) return null
    return appendAgentEvent(
        userId = invocation.userId,
        chatId = invocation.chatId,
        executionId = invocation.executionId,
        type = AgentEventType.TOOL_CALL_FAILED,
        payload = ToolCallFailedPayload(
            toolCallId = toolCallId,
            name = invocation.toolName,
            error = error,
            durationMs = durationMs,
        ),
        createdAt = now,
    )
}

private const val TOOL_EVENTS_METADATA_KEY = "showToolEvents"

private fun Connection.deletePermissionCheckpoint(executionId: UUID) {
    prepareStatement(
        """
        update agent_tool_invocations invocation
        set phase = 'failed',
            error_code = coalesce(execution.error_code, 'execution_terminated'),
            finished_at = coalesce(invocation.finished_at, now()),
            updated_at = now()
        from agent_executions execution
        where execution.id = invocation.execution_id
          and invocation.execution_id = ?
          and execution.status in ('cancelled', 'completed', 'failed')
          and invocation.phase not in ('result_stored', 'failed')
        """.trimIndent()
    ).use { statement ->
        statement.setObject(1, executionId)
        statement.executeUpdate()
    }
    prepareStatement(
        "delete from agent_execution_checkpoints where execution_id = ?"
    ).use { statement ->
        statement.setObject(1, executionId)
        statement.executeUpdate()
    }
}

private fun Connection.requireExecutionForUpdate(executionId: UUID): AgentExecution =
    prepareStatement("select * from agent_executions where id = ? for update").use { statement ->
        statement.setObject(1, executionId)
        statement.executeQuery().use { resultSet ->
            check(resultSet.next()) { "Execution $executionId does not exist." }
            resultSet.toExecution()
        }
    }

private fun Connection.updateExecutionStatus(
    execution: AgentExecution,
    status: AgentExecutionStatus,
    now: Instant,
    usage: ru.souz.backend.execution.model.AgentExecutionUsage? = null,
): AgentExecution {
    prepareStatement(
        """
        update agent_executions
        set status = ?, finished_at = null, cancel_requested = false,
            error_code = null, error_message = null,
            usage_json = coalesce(?, usage_json)
        where id = ?
        """.trimIndent()
    ).use { statement ->
        statement.setString(1, status.value)
        statement.setJson(2, usage?.let { value ->
            postgresStorageMapper.writeValueAsString(
                StoredExecutionUsage(
                    promptTokens = value.promptTokens,
                    completionTokens = value.completionTokens,
                    totalTokens = value.totalTokens,
                    precachedTokens = value.precachedTokens,
                )
            )
        })
        statement.setObject(3, execution.id)
        statement.executeUpdate()
    }
    return execution.copy(
        status = status,
        finishedAt = null,
        cancelRequested = false,
        errorCode = null,
        errorMessage = null,
        usage = usage ?: execution.usage,
    )
}

private fun Connection.updateExecutionTerminal(
    execution: AgentExecution,
    status: AgentExecutionStatus,
    errorCode: String,
    errorMessage: String,
    now: Instant,
) {
    prepareStatement(
        """
        update agent_executions
        set status = ?, finished_at = ?, cancel_requested = ?,
            error_code = ?, error_message = ?
        where id = ?
        """.trimIndent()
    ).use { statement ->
        statement.setString(1, status.value)
        statement.setInstant(2, now)
        statement.setBoolean(3, status == AgentExecutionStatus.CANCELLED)
        statement.setString(4, errorCode)
        statement.setString(5, errorMessage)
        statement.setObject(6, execution.id)
        statement.executeUpdate()
    }
}

private fun Connection.checkpoint(executionId: UUID): AgentExecutionCheckpointRecord? =
    prepareStatement("select * from agent_execution_checkpoints where execution_id = ?").use { statement ->
        statement.setObject(1, executionId)
        statement.executeQuery().use { resultSet ->
            if (resultSet.next()) resultSet.toPermissionCheckpoint() else null
        }
    }

private fun Connection.checkpointForUpdate(executionId: UUID): AgentExecutionCheckpointRecord? =
    prepareStatement(
        "select * from agent_execution_checkpoints where execution_id = ? for update"
    ).use { statement ->
        statement.setObject(1, executionId)
        statement.executeQuery().use { resultSet ->
            if (resultSet.next()) resultSet.toPermissionCheckpoint() else null
        }
    }

private fun Connection.requireCheckpoint(executionId: UUID): AgentExecutionCheckpointRecord =
    checkNotNull(checkpoint(executionId)) { "Checkpoint for execution $executionId does not exist." }

private fun Connection.requireCheckpointForUpdate(executionId: UUID): AgentExecutionCheckpointRecord =
    checkNotNull(checkpointForUpdate(executionId)) { "Checkpoint for execution $executionId does not exist." }

private fun Connection.updateCheckpointPhase(
    executionId: UUID,
    phase: PermissionCheckpointPhase,
    now: Instant,
    clearLease: Boolean = false,
) {
    val leaseSql = if (clearLease) ", lease_token = null, lease_expires_at = null" else ""
    prepareStatement(
        """
        update agent_execution_checkpoints
        set phase = ?, updated_at = ?, row_version = row_version + 1$leaseSql
        where execution_id = ?
        """.trimIndent()
    ).use { statement ->
        statement.setString(1, phase.value)
        statement.setInstant(2, now)
        statement.setObject(3, executionId)
        check(statement.executeUpdate() == 1) { "Checkpoint phase update failed." }
    }
}

private fun Connection.requireInvocation(
    executionId: UUID,
    invocationId: UUID,
): AgentToolInvocationRecord =
    prepareStatement(
        "select * from agent_tool_invocations where execution_id = ? and invocation_id = ?"
    ).use { statement ->
        statement.setObject(1, executionId)
        statement.setObject(2, invocationId)
        statement.executeQuery().use { resultSet ->
            check(resultSet.next()) { "Tool invocation $invocationId does not exist." }
            resultSet.toPermissionInvocation()
        }
    }

private fun Connection.requireInvocationForUpdate(
    executionId: UUID,
    invocationId: UUID,
): AgentToolInvocationRecord =
    prepareStatement(
        """
        select * from agent_tool_invocations
        where execution_id = ? and invocation_id = ? for update
        """.trimIndent()
    ).use { statement ->
        statement.setObject(1, executionId)
        statement.setObject(2, invocationId)
        statement.executeQuery().use { resultSet ->
            check(resultSet.next()) { "Tool invocation $invocationId does not exist." }
            resultSet.toPermissionInvocation()
        }
    }

private fun Connection.updateInvocationPhase(
    executionId: UUID,
    invocationId: UUID,
    phase: PermissionInvocationPhase,
    now: Instant,
) {
    prepareStatement(
        """
        update agent_tool_invocations set phase = ?, updated_at = ?
        where execution_id = ? and invocation_id = ?
        """.trimIndent()
    ).use { statement ->
        statement.setString(1, phase.value)
        statement.setInstant(2, now)
        statement.setObject(3, executionId)
        statement.setObject(4, invocationId)
        check(statement.executeUpdate() == 1) { "Tool invocation phase update failed." }
    }
}

private fun Connection.permissionForInvocation(
    executionId: UUID,
    invocationId: UUID,
    forUpdate: Boolean,
): PermissionRequest? {
    val suffix = if (forUpdate) " for update" else ""
    return prepareStatement(
        "select * from permission_requests where execution_id = ? and invocation_id = ?$suffix"
    ).use { statement ->
        statement.setObject(1, executionId)
        statement.setObject(2, invocationId)
        statement.executeQuery().use { resultSet ->
            if (resultSet.next()) resultSet.toPermissionRequest() else null
        }
    }
}

private fun Connection.permissionById(id: UUID, forUpdate: Boolean): PermissionRequest? {
    val suffix = if (forUpdate) " for update" else ""
    return prepareStatement("select * from permission_requests where id = ?$suffix").use { statement ->
        statement.setObject(1, id)
        statement.executeQuery().use { resultSet ->
            if (resultSet.next()) resultSet.toPermissionRequest() else null
        }
    }
}

private fun Connection.insertPermission(request: PermissionRequest, promptHash: String) {
    prepareStatement(
        """
        insert into permission_requests(
          id, user_id, chat_id, execution_id, invocation_id, tool_call_id,
          tool_name, description, display_params_json, prompt_hash, status,
          created_at, decided_at
        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, null)
        """.trimIndent()
    ).use { statement ->
        statement.setObject(1, request.id)
        statement.setString(2, request.userId)
        statement.setObject(3, request.chatId)
        statement.setObject(4, request.executionId)
        statement.setObject(5, request.invocationId)
        statement.setString(6, request.toolCallId)
        statement.setString(7, request.toolName)
        statement.setString(8, request.description)
        statement.setJson(9, postgresStorageMapper.writeValueAsString(request.displayParams))
        statement.setString(10, promptHash)
        statement.setString(11, request.status.value)
        statement.setInstant(12, request.createdAt)
        statement.executeUpdate()
    }
}

private fun ResultSet.toPermissionRequest(): PermissionRequest = PermissionRequest(
    id = getObject("id", UUID::class.java),
    userId = getString("user_id"),
    chatId = getObject("chat_id", UUID::class.java),
    executionId = getObject("execution_id", UUID::class.java),
    invocationId = getObject("invocation_id", UUID::class.java),
    toolName = getString("tool_name"),
    toolCallId = getString("tool_call_id"),
    description = getString("description"),
    displayParams = postgresStorageMapper.readValue<Map<String, String>>(getString("display_params_json")),
    status = parsePermissionStatus(getString("status")),
    createdAt = instant("created_at"),
    resolvedAt = getObject("decided_at", OffsetDateTime::class.java)?.toInstant(),
)

private fun ResultSet.toPermissionCheckpoint(): AgentExecutionCheckpointRecord =
    AgentExecutionCheckpointRecord(
        executionId = getObject("execution_id", UUID::class.java),
        userId = getString("user_id"),
        chatId = getObject("chat_id", UUID::class.java),
        schemaVersion = getInt("schema_version"),
        revision = getLong("revision"),
        phase = PermissionCheckpointPhase.entries.first { it.value == getString("phase") },
        contextJson = getString("context_json"),
        batchJson = getString("batch_json"),
        nextOrdinal = getInt("next_ordinal"),
        baseStateRowVersion = getLong("base_state_row_version"),
        compatibilityKey = getString("compatibility_key"),
        leaseToken = getObject("lease_token", UUID::class.java),
        leaseExpiresAt = getObject("lease_expires_at", OffsetDateTime::class.java)?.toInstant(),
        createdAt = instant("created_at"),
        updatedAt = instant("updated_at"),
        rowVersion = getLong("row_version"),
    )

private fun ResultSet.toPermissionInvocation(): AgentToolInvocationRecord =
    AgentToolInvocationRecord(
        executionId = getObject("execution_id", UUID::class.java),
        invocationId = getObject("invocation_id", UUID::class.java),
        userId = getString("user_id"),
        chatId = getObject("chat_id", UUID::class.java),
        batchRevision = getLong("batch_revision"),
        ordinal = getInt("ordinal"),
        providerCallId = getString("provider_call_id"),
        toolName = getString("tool_name"),
        argumentsJson = getString("arguments_json"),
        argumentsHash = getString("arguments_hash"),
        toolDefinitionHash = getString("tool_definition_hash"),
        phase = PermissionInvocationPhase.entries.first { it.value == getString("phase") },
        resultMessageJson = getString("result_message_json"),
        errorCode = getString("error_code"),
        startedAt = getObject("started_at", OffsetDateTime::class.java)?.toInstant(),
        finishedAt = getObject("finished_at", OffsetDateTime::class.java)?.toInstant(),
        updatedAt = instant("updated_at"),
    )

private fun parsePermissionStatus(raw: String): PermissionRequestStatus =
    PermissionRequestStatus.entries.first { it.value == raw }
