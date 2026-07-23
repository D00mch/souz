package ru.souz.backend.http.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import ru.souz.backend.http.BackendHttpDependencies
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.http.BackendOpenApiSchemas
import ru.souz.backend.http.BackendOpenApiTags
import ru.souz.backend.http.BackendV1PendingPermissionRequestsResponse
import ru.souz.backend.http.BackendV1PermissionDecisionRequest
import ru.souz.backend.http.BackendV1PermissionDecisionResponse
import ru.souz.backend.http.describeV1
import ru.souz.backend.http.jsonBody
import ru.souz.backend.http.jsonResponse
import ru.souz.backend.http.receiveOrV1BadRequest
import ru.souz.backend.http.requireChatId
import ru.souz.backend.http.requireJsonContentV1
import ru.souz.backend.http.requirePermissionRequestId
import ru.souz.backend.http.requirePermissionsEnabled
import ru.souz.backend.http.requireUserIdFromTrustedProxy
import ru.souz.backend.http.requireV1Service
import ru.souz.backend.http.toDto
import ru.souz.backend.http.toPermissionDecision
import ru.souz.backend.http.toResponse
import ru.souz.backend.http.uuidPathParameter
import ru.souz.backend.http.v1ErrorResponses

internal fun Route.permissionRoutes(deps: BackendHttpDependencies) {
    get(BackendHttpRoutes.CHAT_PENDING_PERMISSION_REQUESTS_PATTERN) {
        requirePermissionsEnabled(deps.featureFlags)
        val service = requireV1Service(deps.permissionService, "Permission")
        call.respond(
            BackendV1PendingPermissionRequestsResponse(
                items = service.listPending(
                    userId = call.requireUserIdFromTrustedProxy(),
                    chatId = call.requireChatId(),
                ).map { it.toDto() },
            )
        )
    }.describeV1(
        operationId = "listPendingPermissionRequests",
        tag = BackendOpenApiTags.PERMISSIONS,
        summary = "List pending permission requests",
        description = "Returns the authoritative pending permission snapshot for an owned chat. Returns 404 for an unknown or foreign-owned chat, and 404 feature_disabled when backend permissions are disabled.",
    ) {
        parameters { uuidPathParameter("chatId", "Owned chat UUID.") }
        responses {
            jsonResponse<BackendV1PendingPermissionRequestsResponse>(
                HttpStatusCode.OK,
                "Pending permission requests in creation order.",
            )
            v1ErrorResponses(HttpStatusCode.BadRequest, HttpStatusCode.NotFound)
        }
    }

    put(BackendHttpRoutes.PERMISSION_DECISION_PATTERN) {
        requirePermissionsEnabled(deps.featureFlags)
        val service = requireV1Service(deps.permissionService, "Permission")
        call.requireJsonContentV1()
        val request = call.receiveOrV1BadRequest<BackendV1PermissionDecisionRequest>()
        call.respond(
            service.decide(
                userId = call.requireUserIdFromTrustedProxy(),
                permissionRequestId = call.requirePermissionRequestId(),
                decision = request.toPermissionDecision(),
            ).toResponse()
        )
    }.describeV1(
        operationId = "decidePermissionRequest",
        tag = BackendOpenApiTags.PERMISSIONS,
        summary = "Decide a permission request",
        description = "Atomically grants or denies an owned permission request and queues asynchronous continuation. Repeating the same decision is idempotent; conflicting decisions and cancelled requests return 409. Missing and foreign-owned IDs return the same 404 response. Returns 404 feature_disabled when backend permissions are disabled.",
    ) {
        parameters { uuidPathParameter("id", "Permission request UUID.") }
        requestBody {
            jsonBody<BackendV1PermissionDecisionRequest>(
                description = "A grant or deny decision.",
                schemaTransform = BackendOpenApiSchemas::permissionDecision,
            )
        }
        responses {
            jsonResponse<BackendV1PermissionDecisionResponse>(
                HttpStatusCode.OK,
                "Resolved permission request and its current execution.",
            )
            v1ErrorResponses(HttpStatusCode.BadRequest, HttpStatusCode.NotFound, HttpStatusCode.Conflict)
        }
    }
}
