package ru.souz.backend.http

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.permission.model.PermissionDecision
import ru.souz.backend.permission.model.PermissionRequest
import ru.souz.backend.permission.model.PermissionRequestStatus
import ru.souz.backend.permission.service.PermissionDecisionResult
import ru.souz.backend.permission.service.PermissionService

class BackendPermissionRouteTest {
    private val json = jacksonObjectMapper()

    @Test
    fun `permission routes return feature disabled before resolving the service`() = testApplication {
        val context = routeTestContext(featureFlags = BackendFeatureFlags(permissions = false))
        installPermissionApplication(context, permissionService = null)
        val requestId = UUID.randomUUID()

        val pending = client.get(BackendHttpRoutes.pendingPermissionRequests(UUID.randomUUID())) {
            trustedHeaders("user-a")
        }
        val decision = client.put(BackendHttpRoutes.permissionDecision(requestId)) {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"decision":"grant"}""")
        }

        listOf(pending, decision).forEach { response ->
            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals("feature_disabled", json.readTree(response.bodyAsText())["error"]["code"].asText())
        }
    }

    @Test
    fun `pending snapshot is owner scoped bounded and contains no private workflow data`() = testApplication {
        val chatId = UUID.randomUUID()
        val permission = permissionRequest(
            userId = "user-a",
            chatId = chatId,
            description = "x".repeat(PermissionRequest.MAX_DESCRIPTION_LENGTH + 40),
            displayParams = (1..(PermissionRequest.MAX_DISPLAY_PARAMS + 4)).associate { index ->
                "key-$index" to "v".repeat(PermissionRequest.MAX_DISPLAY_PARAM_VALUE_LENGTH + 20)
            },
        )
        val service = FakePermissionService(
            chatOwners = mapOf(chatId to "user-a"),
            initialRequests = listOf(permission),
            execution = execution(permission),
        )
        val context = routeTestContext(featureFlags = BackendFeatureFlags(permissions = true))
        installPermissionApplication(context, service)

        val response = client.get(BackendHttpRoutes.pendingPermissionRequests(chatId)) {
            trustedHeaders("user-a")
        }
        val item = json.readTree(response.bodyAsText())["items"].single()

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(permission.id.toString(), item["id"].asText())
        assertEquals(permission.invocationId.toString(), item["invocationId"].asText())
        assertEquals("provider-call-1", item["toolCallId"].asText())
        assertEquals(PermissionRequest.MAX_DESCRIPTION_LENGTH, item["description"].asText().length)
        assertEquals(PermissionRequest.MAX_DISPLAY_PARAMS, item["displayParams"].size())
        assertTrue(
            item["displayParams"].properties().asSequence().all { (_, value) ->
                value.asText().length <= PermissionRequest.MAX_DISPLAY_PARAM_VALUE_LENGTH
            }
        )
        assertFalse(item.has("userId"))
        assertFalse(item.has("arguments"))
        assertFalse(item.has("promptHash"))
        assertFalse(item.has("checkpoint"))
        assertEquals("user-a", service.lastListUserId)
        assertEquals(chatId, service.lastListChatId)
    }

    @Test
    fun `grant decision is parsed and returns the resolved request plus queued execution`() = testApplication {
        val permission = permissionRequest(userId = "user-a")
        val service = FakePermissionService(
            chatOwners = mapOf(permission.chatId to "user-a"),
            initialRequests = listOf(permission),
            execution = execution(permission),
        )
        val context = routeTestContext(featureFlags = BackendFeatureFlags(permissions = true))
        installPermissionApplication(context, service)

        val first = client.put(BackendHttpRoutes.permissionDecision(permission.id)) {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"decision":"grant"}""")
        }
        val repeated = client.put(BackendHttpRoutes.permissionDecision(permission.id)) {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"decision":"grant"}""")
        }
        val firstPayload = json.readTree(first.bodyAsText())
        val repeatedPayload = json.readTree(repeated.bodyAsText())

        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(HttpStatusCode.OK, repeated.status)
        assertEquals("granted", firstPayload["permissionRequest"]["status"].asText())
        assertEquals("queued", firstPayload["execution"]["status"].asText())
        assertEquals(firstPayload, repeatedPayload)
        assertEquals(PermissionDecision.GRANT, service.lastDecision)
        assertEquals(1, service.resolutionCount)
    }

    @Test
    fun `foreign and missing decisions have identical not found responses`() = testApplication {
        val permission = permissionRequest(userId = "user-a")
        val service = FakePermissionService(
            chatOwners = mapOf(permission.chatId to "user-a"),
            initialRequests = listOf(permission),
            execution = execution(permission),
        )
        val context = routeTestContext(featureFlags = BackendFeatureFlags(permissions = true))
        installPermissionApplication(context, service)

        suspend fun decide(userId: String, id: UUID) =
            client.put(BackendHttpRoutes.permissionDecision(id)) {
                trustedHeaders(userId)
                contentType(ContentType.Application.Json)
                setBody("""{"decision":"deny"}""")
            }

        val foreign = decide("user-b", permission.id)
        val missing = decide("user-b", UUID.randomUUID())

        assertEquals(HttpStatusCode.NotFound, foreign.status)
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals(foreign.bodyAsText(), missing.bodyAsText())
    }

    @Test
    fun `conflicting decision and cancelled request return conflict`() = testApplication {
        val permission = permissionRequest(userId = "user-a")
        val cancelled = permissionRequest(
            userId = "user-a",
            status = PermissionRequestStatus.CANCELLED,
            resolvedAt = Instant.parse("2026-07-23T11:00:00Z"),
        )
        val service = FakePermissionService(
            chatOwners = mapOf(permission.chatId to "user-a", cancelled.chatId to "user-a"),
            initialRequests = listOf(permission, cancelled),
            execution = execution(permission),
        )
        val context = routeTestContext(featureFlags = BackendFeatureFlags(permissions = true))
        installPermissionApplication(context, service)

        client.put(BackendHttpRoutes.permissionDecision(permission.id)) {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"decision":"grant"}""")
        }
        val conflicting = client.put(BackendHttpRoutes.permissionDecision(permission.id)) {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"decision":"deny"}""")
        }
        val cancelledDecision = client.put(BackendHttpRoutes.permissionDecision(cancelled.id)) {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"decision":"grant"}""")
        }

        listOf(conflicting, cancelledDecision).forEach { response ->
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals(
                "permission_decision_conflict",
                json.readTree(response.bodyAsText())["error"]["code"].asText(),
            )
        }
    }

    @Test
    fun `decision validates content type UUID and exact decision values`() = testApplication {
        val service = FakePermissionService(emptyMap(), emptyList(), execution = null)
        val context = routeTestContext(featureFlags = BackendFeatureFlags(permissions = true))
        installPermissionApplication(context, service)

        val wrongContentType = client.put(BackendHttpRoutes.permissionDecision(UUID.randomUUID())) {
            trustedHeaders("user-a")
            setBody("""{"decision":"grant"}""")
        }
        val malformedId = client.put(BackendHttpRoutes.permissionDecision("not-a-uuid")) {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"decision":"grant"}""")
        }
        val invalidDecision = client.put(BackendHttpRoutes.permissionDecision(UUID.randomUUID())) {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"decision":"GRANT"}""")
        }
        val paddedDecision = client.put(BackendHttpRoutes.permissionDecision(UUID.randomUUID())) {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"decision":" grant "}""")
        }

        listOf(wrongContentType, malformedId, invalidDecision, paddedDecision).forEach { response ->
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("invalid_request", json.readTree(response.bodyAsText())["error"]["code"].asText())
        }
    }

    private fun ApplicationTestBuilder.installPermissionApplication(
        context: RouteTestContext,
        permissionService: PermissionService?,
    ) {
        application {
            backendApplication(
                BackendHttpDependencies(
                    bootstrapService = context.bootstrapService,
                    selectedModel = { context.settingsProvider.gigaModel.alias },
                    permissionService = permissionService,
                    featureFlags = context.featureFlags,
                    trustedProxyToken = { "proxy-secret" },
                )
            )
        }
    }
}

private class FakePermissionService(
    private val chatOwners: Map<UUID, String>,
    initialRequests: List<PermissionRequest>,
    private val execution: AgentExecution?,
) : PermissionService {
    private val requests = initialRequests.associateByTo(LinkedHashMap(), PermissionRequest::id)

    var lastListUserId: String? = null
        private set
    var lastListChatId: UUID? = null
        private set
    var lastDecision: PermissionDecision? = null
        private set
    var resolutionCount: Int = 0
        private set

    override suspend fun listPending(userId: String, chatId: UUID): List<PermissionRequest> {
        lastListUserId = userId
        lastListChatId = chatId
        if (chatOwners[chatId] != userId) throw chatNotFound()
        return requests.values.filter { request ->
            request.userId == userId && request.chatId == chatId && request.status == PermissionRequestStatus.PENDING
        }
    }

    override suspend fun decide(
        userId: String,
        permissionRequestId: UUID,
        decision: PermissionDecision,
    ): PermissionDecisionResult {
        lastDecision = decision
        val request = requests[permissionRequestId]
            ?.takeIf { it.userId == userId }
            ?: throw permissionNotFound()
        val decidedStatus = when (decision) {
            PermissionDecision.GRANT -> PermissionRequestStatus.GRANTED
            PermissionDecision.DENY -> PermissionRequestStatus.DENIED
        }
        val resolved = when (request.status) {
            PermissionRequestStatus.PENDING -> request.copy(
                status = decidedStatus,
                resolvedAt = Instant.parse("2026-07-23T12:00:00Z"),
            ).also {
                requests[request.id] = it
                resolutionCount += 1
            }

            decidedStatus -> request
            PermissionRequestStatus.GRANTED,
            PermissionRequestStatus.DENIED,
            PermissionRequestStatus.CANCELLED,
            -> throw permissionConflict()
        }
        return PermissionDecisionResult(
            permissionRequest = resolved,
            execution = requireNotNull(execution).copy(status = AgentExecutionStatus.QUEUED),
        )
    }

    private fun chatNotFound(): BackendV1Exception =
        BackendV1Exception(HttpStatusCode.NotFound, "chat_not_found", "Chat not found.")

    private fun permissionNotFound(): BackendV1Exception =
        BackendV1Exception(
            HttpStatusCode.NotFound,
            "permission_request_not_found",
            "Permission request not found.",
        )

    private fun permissionConflict(): BackendV1Exception =
        BackendV1Exception(
            HttpStatusCode.Conflict,
            "permission_decision_conflict",
            "Permission request cannot accept this decision.",
        )
}

private fun permissionRequest(
    userId: String,
    chatId: UUID = UUID.randomUUID(),
    description: String = "Allow the fixture effect?",
    displayParams: Map<String, String> = mapOf("target" to "fixture"),
    status: PermissionRequestStatus = PermissionRequestStatus.PENDING,
    resolvedAt: Instant? = null,
): PermissionRequest =
    PermissionRequest(
        id = UUID.randomUUID(),
        userId = userId,
        chatId = chatId,
        executionId = UUID.randomUUID(),
        invocationId = UUID.randomUUID(),
        toolName = "PermissionFixture",
        toolCallId = "provider-call-1",
        description = description,
        displayParams = displayParams,
        status = status,
        createdAt = Instant.parse("2026-07-23T10:00:00Z"),
        resolvedAt = resolvedAt,
    )

private fun execution(request: PermissionRequest): AgentExecution =
    AgentExecution(
        id = request.executionId,
        userId = request.userId,
        chatId = request.chatId,
        userMessageId = UUID.randomUUID(),
        assistantMessageId = null,
        status = AgentExecutionStatus.WAITING_PERMISSION,
        requestId = "request-1",
        clientMessageId = "client-1",
        model = null,
        provider = null,
        startedAt = Instant.parse("2026-07-23T09:59:00Z"),
        finishedAt = null,
        cancelRequested = false,
        errorCode = null,
        errorMessage = null,
        usage = null,
        metadata = emptyMap(),
    )
