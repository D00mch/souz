package ru.souz.backend.storage.postgres

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import ru.souz.agent.AgentId
import ru.souz.backend.agent.runtime.BackendAgentContextCheckpointV1
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.execution.model.AgentExecutionUsage
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.permission.model.PermissionDecision
import ru.souz.backend.permission.model.PermissionRequestStatus
import ru.souz.backend.permission.repository.ParkPermissionCommand
import ru.souz.backend.permission.repository.PermissionInvocationPhase
import ru.souz.backend.permission.repository.PlannedToolInvocation
import ru.souz.backend.permission.repository.SaveToolBatchCommand
import ru.souz.backend.permission.repository.StoredPermissionDecisionResult
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest

class PostgresPermissionWorkflowRepositoryTest {
    @Test
    fun `permission wait decision and resume claim are durable and idempotent`() = runTest {
        val schema = newPostgresSchema("permission_workflow")
        val dataSource = PostgresDataSourceFactory.create(postgresAppConfig(schema).postgres)
        val users = PostgresUserRepository(dataSource)
        val chats = PostgresChatRepository(dataSource)
        val executions = PostgresAgentExecutionRepository(dataSource)
        val workflow = PostgresPermissionWorkflowRepository(dataSource)
        val now = Instant.parse("2026-07-23T10:00:00Z")
        val userId = "permission-user"
        val chatId = UUID.randomUUID()
        val executionId = UUID.randomUUID()
        val invocationId = UUID.randomUUID()
        val initialContext = checkpointContext("before permission")
        val advancedContext = checkpointContext("after permission")

        dataSource.use {
            users.ensureUser(userId)
            chats.create(
                Chat(
                    id = chatId,
                    userId = userId,
                    title = "Permission test",
                    archived = false,
                    createdAt = now,
                    updatedAt = now,
                )
            )
            executions.create(
                AgentExecution(
                    id = executionId,
                    userId = userId,
                    chatId = chatId,
                    userMessageId = null,
                    assistantMessageId = null,
                    status = AgentExecutionStatus.RUNNING,
                    requestId = null,
                    clientMessageId = null,
                    model = LLMModel.Max,
                    provider = LLMModel.Max.provider,
                    startedAt = now,
                    finishedAt = null,
                    cancelRequested = false,
                    errorCode = null,
                    errorMessage = null,
                    usage = null,
                    metadata = emptyMap(),
                )
            )

            workflow.saveToolBatch(
                SaveToolBatchCommand(
                    executionId = executionId,
                    userId = userId,
                    chatId = chatId,
                    schemaVersion = 1,
                    revision = 1,
                    contextJson = initialContext,
                    batchJson = "{}",
                    baseStateRowVersion = 0,
                    compatibilityKey = "tool-hash",
                    invocations = listOf(
                        PlannedToolInvocation(
                            invocationId = invocationId,
                            ordinal = 0,
                            providerCallId = "provider-call",
                            toolName = "PermissionFixture",
                            argumentsJson = "{}",
                            argumentsHash = "arguments-hash",
                            toolDefinitionHash = "tool-hash",
                        )
                    ),
                    now = now,
                )
            )
            workflow.beginInvocation(executionId, invocationId, now)
            val parkedUsage = AgentExecutionUsage(1, 2, 3, 0)
            val parked = workflow.park(
                ParkPermissionCommand(
                    executionId = executionId,
                    userId = userId,
                    chatId = chatId,
                    invocationId = invocationId,
                    toolCallId = "provider-call",
                    toolName = "PermissionFixture",
                    description = "Allow fixture effect",
                    displayParams = mapOf("target" to "fixture"),
                    promptHash = "prompt-hash",
                    usage = parkedUsage,
                    now = now,
                )
            )
            assertEquals(AgentExecutionStatus.WAITING_PERMISSION, parked.execution.status)
            assertEquals(parkedUsage, parked.execution.usage)
            assertEquals(AgentEventType.PERMISSION_REQUESTED, parked.event?.type)
            assertEquals(listOf(parked.permissionRequest), workflow.listPending(userId, chatId))

            val decided = assertIs<StoredPermissionDecisionResult.Updated>(
                workflow.decide(userId, parked.permissionRequest.id, PermissionDecision.GRANT, now.plusSeconds(1))
            )
            assertEquals(PermissionRequestStatus.GRANTED, decided.transition.permissionRequest.status)
            assertEquals(AgentExecutionStatus.QUEUED, decided.transition.execution.status)
            assertEquals(AgentEventType.PERMISSION_RESOLVED, decided.transition.event?.type)
            assertIs<StoredPermissionDecisionResult.Idempotent>(
                workflow.decide(userId, parked.permissionRequest.id, PermissionDecision.GRANT, now.plusSeconds(2))
            )
            assertIs<StoredPermissionDecisionResult.Conflict>(
                workflow.decide(userId, parked.permissionRequest.id, PermissionDecision.DENY, now.plusSeconds(2))
            )

            val leaseToken = UUID.randomUUID()
            val claimed = assertNotNull(
                workflow.claimReady(
                    executionId = executionId,
                    leaseToken = leaseToken,
                    leaseExpiresAt = now.plus(2, ChronoUnit.MINUTES),
                    now = now.plusSeconds(3),
                )
            )
            assertEquals(AgentExecutionStatus.RUNNING, claimed.execution.status)
            assertTrue(workflow.isClaimActive(executionId, leaseToken, now.plusSeconds(3)))
            assertTrue(workflow.markExecuting(parked.permissionRequest.id, leaseToken, now.plusSeconds(4)))
            val stored = workflow.storeToolResult(
                executionId = executionId,
                invocationId = invocationId,
                resultMessageJson = "{}",
                contextJson = advancedContext,
                nextOrdinal = 1,
                now = now.plusSeconds(5),
            )
            assertEquals(1, stored.nextOrdinal)
            assertEquals(
                postgresStorageMapper.readTree(advancedContext),
                postgresStorageMapper.readTree(stored.contextJson),
            )
            assertNull(
                workflow.claimReady(
                    executionId = executionId,
                    leaseToken = UUID.randomUUID(),
                    leaseExpiresAt = now.plus(3, ChronoUnit.MINUTES),
                    now = now.plusSeconds(6),
                ),
                "A running continuation must not be reclaimed between checkpoint writes.",
            )
            assertTrue(workflow.requeueForRecovery(executionId, now.plusSeconds(7)))
            val recoveredClaim = assertNotNull(
                workflow.claimReady(
                    executionId = executionId,
                    leaseToken = UUID.randomUUID(),
                    leaseExpiresAt = now.plus(3, ChronoUnit.MINUTES),
                    now = now.plusSeconds(8),
                )
            )
            assertEquals(PermissionInvocationPhase.RESULT_STORED, recoveredClaim.invocation.phase)

            executions.update(
                recoveredClaim.execution.copy(status = AgentExecutionStatus.WAITING_OPTION)
            )
            assertFalse(workflow.requeueForRecovery(executionId, now.plusSeconds(9)))
            assertNull(workflow.getCheckpoint(executionId))
            assertEquals(
                "after permission",
                PostgresAgentStateRepository(dataSource)
                    .get(userId, chatId)
                    ?.history
                    ?.lastOrNull()
                    ?.content,
            )
        }
    }
}

private fun checkpointContext(historyContent: String): String =
    postgresStorageMapper.writeValueAsString(
        BackendAgentContextCheckpointV1(
            activeAgentId = AgentId.default.storageValue,
            originalPrompt = "permission test",
            history = listOf(LLMRequest.Message(LLMMessageRole.function, historyContent)),
            systemPrompt = "system",
            model = LLMModel.Max.alias,
            temperature = 0.5f,
            contextSize = 4_096,
            activeTools = emptyList(),
        )
    )
