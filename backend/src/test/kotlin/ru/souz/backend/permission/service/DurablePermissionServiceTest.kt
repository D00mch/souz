package ru.souz.backend.permission.service

import java.lang.reflect.Proxy
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import ru.souz.backend.chat.repository.ChatRepository
import ru.souz.backend.events.bus.AgentEventBus
import ru.souz.backend.events.repository.AgentEventRepository
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.permission.model.PermissionDecision
import ru.souz.backend.permission.model.PermissionRequest
import ru.souz.backend.permission.model.PermissionRequestStatus
import ru.souz.backend.permission.repository.PermissionWorkflowRepository
import ru.souz.backend.permission.repository.StoredPermissionDecisionResult

class DurablePermissionServiceTest {
    @Test
    fun `idempotent decision redispatches without creating another transition`() = runTest {
        val request = permissionRequest()
        val execution = execution(request)
        val workflow = proxy<PermissionWorkflowRepository> { method, _ ->
            when (method.name) {
                "decide" -> StoredPermissionDecisionResult.Idempotent(request, execution)
                else -> error("Unexpected workflow call ${method.name}")
            }
        }
        val dispatched = mutableListOf<UUID>()
        val service = DurablePermissionService(
            chatRepository = proxy { method, _ -> error("Unexpected chat call ${method.name}") },
            workflowRepository = workflow,
            eventService = AgentEventService(
                chatRepository = proxy { method, _ -> error("Unexpected chat call ${method.name}") },
                eventRepository = proxy<AgentEventRepository> { method, _ ->
                    error("Unexpected event repository call ${method.name}")
                },
                eventBus = AgentEventBus(),
            ),
            continuationDispatcher = PermissionContinuationDispatcher { dispatched += it },
        )

        val result = service.decide(request.userId, request.id, PermissionDecision.GRANT)

        assertEquals(request, result.permissionRequest)
        assertEquals(execution, result.execution)
        assertEquals(listOf(execution.id), dispatched)
    }

    @Test
    fun `idempotent decision does not redispatch a running continuation`() = runTest {
        val request = permissionRequest()
        val execution = execution(request).copy(status = AgentExecutionStatus.RUNNING)
        val workflow = proxy<PermissionWorkflowRepository> { method, _ ->
            when (method.name) {
                "decide" -> StoredPermissionDecisionResult.Idempotent(request, execution)
                else -> error("Unexpected workflow call ${method.name}")
            }
        }
        val dispatched = mutableListOf<UUID>()
        val service = DurablePermissionService(
            chatRepository = proxy { method, _ -> error("Unexpected chat call ${method.name}") },
            workflowRepository = workflow,
            eventService = AgentEventService(
                chatRepository = proxy { method, _ -> error("Unexpected chat call ${method.name}") },
                eventRepository = proxy<AgentEventRepository> { method, _ ->
                    error("Unexpected event repository call ${method.name}")
                },
                eventBus = AgentEventBus(),
            ),
            continuationDispatcher = PermissionContinuationDispatcher { dispatched += it },
        )

        service.decide(request.userId, request.id, PermissionDecision.GRANT)

        assertEquals(emptyList(), dispatched)
    }
}

private inline fun <reified T : Any> proxy(
    crossinline handler: (java.lang.reflect.Method, Array<out Any?>?) -> Any?,
): T = Proxy.newProxyInstance(
    T::class.java.classLoader,
    arrayOf(T::class.java),
) { proxy, method, args ->
    when (method.name) {
        "toString" -> "${T::class.simpleName}TestProxy"
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> proxy === args?.firstOrNull()
        else -> handler(method, args)
    }
} as T

private fun permissionRequest(): PermissionRequest = PermissionRequest(
    id = UUID.randomUUID(),
    userId = "permission-user",
    chatId = UUID.randomUUID(),
    executionId = UUID.randomUUID(),
    invocationId = UUID.randomUUID(),
    toolName = "PermissionFixture",
    toolCallId = "provider-call",
    description = "Allow fixture effect",
    displayParams = mapOf("target" to "fixture"),
    status = PermissionRequestStatus.GRANTED,
    createdAt = Instant.parse("2026-07-23T10:00:00Z"),
    resolvedAt = Instant.parse("2026-07-23T10:00:01Z"),
)

private fun execution(request: PermissionRequest): AgentExecution = AgentExecution(
    id = request.executionId,
    userId = request.userId,
    chatId = request.chatId,
    userMessageId = null,
    assistantMessageId = null,
    status = AgentExecutionStatus.QUEUED,
    requestId = null,
    clientMessageId = null,
    model = null,
    provider = null,
    startedAt = request.createdAt,
    finishedAt = null,
    cancelRequested = false,
    errorCode = null,
    errorMessage = null,
    usage = null,
    metadata = emptyMap(),
)
