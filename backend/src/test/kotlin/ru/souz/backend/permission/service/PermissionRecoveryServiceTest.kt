package ru.souz.backend.permission.service

import java.lang.reflect.Proxy
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import ru.souz.backend.chat.repository.ChatRepository
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.events.bus.AgentEventBus
import ru.souz.backend.events.repository.AgentEventRepository
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.permission.repository.AgentExecutionCheckpointRecord
import ru.souz.backend.permission.repository.PermissionCheckpointPhase
import ru.souz.backend.permission.repository.PermissionWorkflowRepository

class PermissionRecoveryServiceTest {
    @Test
    fun `one failed checkpoint does not prevent later recovery candidates`() = runTest {
        val first = checkpoint(PermissionCheckpointPhase.BATCH_READY)
        val second = checkpoint(PermissionCheckpointPhase.BATCH_READY)
        val workflow = recoveryProxy<PermissionWorkflowRepository> { method, args ->
            when (method.name) {
                "listRecoveryCandidates" -> listOf(first, second)
                "failUnknownOutcome" -> when (args?.firstOrNull()) {
                    first.executionId -> error("malformed first checkpoint")
                    second.executionId -> emptyList<ru.souz.backend.events.model.AgentEvent>()
                    else -> error("Unexpected execution")
                }
                "requeueForRecovery" -> args?.firstOrNull() == second.executionId
                else -> error("Unexpected workflow call ${method.name}")
            }
        }
        val dispatched = mutableListOf<UUID>()
        val service = PermissionRecoveryService(
            featureFlags = BackendFeatureFlags(permissions = true),
            workflowRepository = workflow,
            continuationDispatcher = PermissionContinuationDispatcher { dispatched += it },
            eventService = AgentEventService(
                chatRepository = recoveryProxy<ChatRepository> { method, _ ->
                    error("Unexpected chat call ${method.name}")
                },
                eventRepository = recoveryProxy<AgentEventRepository> { method, _ ->
                    error("Unexpected event call ${method.name}")
                },
                eventBus = AgentEventBus(),
            ),
            scope = this,
        )

        service.recover()

        assertEquals(listOf(second.executionId), dispatched)
    }
}

private fun checkpoint(phase: PermissionCheckpointPhase): AgentExecutionCheckpointRecord {
    val now = Instant.parse("2026-07-23T10:00:00Z")
    return AgentExecutionCheckpointRecord(
        executionId = UUID.randomUUID(),
        userId = "permission-user",
        chatId = UUID.randomUUID(),
        schemaVersion = 1,
        revision = 1,
        phase = phase,
        contextJson = "{}",
        batchJson = "{}",
        nextOrdinal = 0,
        baseStateRowVersion = 0,
        compatibilityKey = "compatibility",
        leaseToken = null,
        leaseExpiresAt = null,
        createdAt = now,
        updatedAt = now,
        rowVersion = 0,
    )
}

private inline fun <reified T : Any> recoveryProxy(
    crossinline handler: (java.lang.reflect.Method, Array<out Any?>?) -> Any?,
): T = Proxy.newProxyInstance(
    T::class.java.classLoader,
    arrayOf(T::class.java),
) { proxy, method, args ->
    when (method.name) {
        "toString" -> "${T::class.simpleName}RecoveryTestProxy"
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> proxy === args?.firstOrNull()
        else -> handler(method, args)
    }
} as T
