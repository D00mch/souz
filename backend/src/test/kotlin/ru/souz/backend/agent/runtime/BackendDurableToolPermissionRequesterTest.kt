package ru.souz.backend.agent.runtime

import java.lang.reflect.Proxy
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest
import ru.souz.agent.runtime.AgentToolInvocationAttributes
import ru.souz.backend.permission.model.PermissionRequest
import ru.souz.backend.permission.model.PermissionRequestStatus
import ru.souz.backend.permission.repository.PermissionWorkflowRepository
import ru.souz.llms.ToolInvocationMeta
import ru.souz.tool.ToolPermissionResult

class BackendDurableToolPermissionRequesterTest {
    @Test
    fun `first request produces a bounded correlated pause draft`() = runTest {
        val fixture = Fixture()
        val requester = BackendDurableToolPermissionRequester(repository())

        val pause = assertFailsWith<BackendPermissionDraft> {
            requester.requestPermission(
                description = "  Allow fixture effect  ",
                displayParams = linkedMapOf(" target " to " fixture "),
                meta = fixture.meta(),
            )
        }

        assertEquals(fixture.userId, pause.userId)
        assertEquals(fixture.chatId, pause.chatId)
        assertEquals(fixture.executionId, pause.executionId)
        assertEquals(fixture.invocationId, pause.invocationId)
        assertEquals(fixture.toolCallId, pause.toolCallId)
        assertEquals(fixture.toolName, pause.toolName)
        assertEquals("Allow fixture effect", pause.description)
        assertEquals(mapOf("target" to "fixture"), pause.displayParams)
        assertEquals(64, pause.promptHash.length)
    }

    @Test
    fun `resolved request grants or denies only the exact stored invocation`() = runTest {
        val fixture = Fixture()
        val granted = fixture.request(PermissionRequestStatus.GRANTED)
        val requester = BackendDurableToolPermissionRequester(repository(granted))
        val meta = fixture.meta(granted.id)

        assertSame(
            ToolPermissionResult.Ok,
            requester.requestPermission("Allow fixture effect", mapOf("target" to "fixture"), meta),
        )

        val denied = fixture.request(PermissionRequestStatus.DENIED)
        val denial = BackendDurableToolPermissionRequester(repository(denied)).requestPermission(
            "Allow fixture effect",
            mapOf("target" to "fixture"),
            fixture.meta(denied.id),
        )
        assertEquals("User disapproved", assertIs<ToolPermissionResult.No>(denial).msg)

        assertFailsWith<IllegalArgumentException> {
            requester.requestPermission(
                "Changed prompt",
                mapOf("target" to "fixture"),
                meta,
            )
        }
    }

    @Test
    fun `malformed resume identity is rejected instead of opening another request`() = runTest {
        val fixture = Fixture()
        val malformed = fixture.meta().copy(
            attributes = fixture.meta().attributes +
                (AgentToolInvocationAttributes.RESUME_PERMISSION_ID to "not-a-uuid")
        )

        assertFailsWith<IllegalStateException> {
            BackendDurableToolPermissionRequester(repository()).requestPermission(
                "Allow fixture effect",
                mapOf("target" to "fixture"),
                malformed,
            )
        }
    }

    private class Fixture {
        val userId = "permission-user"
        val chatId: UUID = UUID.randomUUID()
        val executionId: UUID = UUID.randomUUID()
        val invocationId: UUID = UUID.randomUUID()
        val toolCallId = "provider-call"
        val toolName = "PermissionFixture"

        fun meta(permissionId: UUID? = null): ToolInvocationMeta = ToolInvocationMeta(
            userId = userId,
            conversationId = chatId.toString(),
            requestId = executionId.toString(),
            attributes = buildMap {
                put(AgentToolInvocationAttributes.INVOCATION_ID, invocationId.toString())
                put(AgentToolInvocationAttributes.TOOL_NAME, toolName)
                put(AgentToolInvocationAttributes.EXECUTION_ID, executionId.toString())
                put(AgentToolInvocationAttributes.PROVIDER_TOOL_CALL_ID, toolCallId)
                permissionId?.let {
                    put(AgentToolInvocationAttributes.RESUME_PERMISSION_ID, it.toString())
                }
            },
        )

        fun request(status: PermissionRequestStatus): PermissionRequest = PermissionRequest(
            id = UUID.randomUUID(),
            userId = userId,
            chatId = chatId,
            executionId = executionId,
            invocationId = invocationId,
            toolName = toolName,
            toolCallId = toolCallId,
            description = "Allow fixture effect",
            displayParams = mapOf("target" to "fixture"),
            status = status,
            createdAt = Instant.parse("2026-07-23T10:00:00Z"),
            resolvedAt = Instant.parse("2026-07-23T10:00:01Z"),
        )
    }
}

private fun repository(request: PermissionRequest? = null): PermissionWorkflowRepository =
    @Suppress("UNCHECKED_CAST")
    (Proxy.newProxyInstance(
        PermissionWorkflowRepository::class.java.classLoader,
        arrayOf(PermissionWorkflowRepository::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getOwned" -> request
            "toString" -> "PermissionWorkflowRepositoryTestProxy"
            else -> error("Unexpected repository call ${method.name}")
        }
    } as PermissionWorkflowRepository)
