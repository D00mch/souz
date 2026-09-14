package ru.souz.backend.http.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import ru.souz.backend.events.model.AgentEventEnvelope
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.events.model.PublicToolCallStartedPayload
import ru.souz.backend.http.BackendHttpDependencies
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.http.BackendEventOpenApiSchemas
import ru.souz.backend.http.BackendOpenApiTags
import ru.souz.backend.http.BackendV1EventsResponse
import ru.souz.backend.http.DEFAULT_EVENT_LIMIT
import ru.souz.backend.http.MAX_EVENT_LIMIT
import ru.souz.backend.http.describeV1
import ru.souz.backend.http.jsonResponse
import ru.souz.backend.http.nonNegativeLongQueryParameter
import ru.souz.backend.http.positiveIntQueryParameter
import ru.souz.backend.http.queryNonNegativeLong
import ru.souz.backend.http.queryPositiveInt
import ru.souz.backend.http.requireChatId
import ru.souz.backend.http.requireUserIdFromTrustedProxy
import ru.souz.backend.http.requireWsEventsEnabled
import ru.souz.backend.http.toDto
import ru.souz.backend.http.uuidPathParameter
import ru.souz.backend.http.v1ErrorResponses

internal fun Route.eventRoutes(deps: BackendHttpDependencies) {
    get(BackendHttpRoutes.CHAT_EVENTS_PATTERN) {
        requireWsEventsEnabled(deps.featureFlags)
        val limit = call.queryPositiveInt("limit", DEFAULT_EVENT_LIMIT, MAX_EVENT_LIMIT)
        call.respond(
            BackendV1EventsResponse(
                items = deps.eventService.listByChat(
                    userId = call.requireUserIdFromTrustedProxy(),
                    chatId = call.requireChatId(),
                    afterSeq = call.queryNonNegativeLong("afterSeq"),
                    limit = limit,
                ).map { it.toDto() },
            )
        )
    }.describeV1(
        operationId = "listChatEvents",
        tag = BackendOpenApiTags.EVENTS,
        summary = "List durable chat events",
        description = "Replays durable events for an owned chat. Canonical events use typed variants, while other stored rows use the compatibility fallback. Newly produced message.delta events remain live-only.",
    ) {
        parameters {
            uuidPathParameter("chatId", "Owned chat UUID.")
            nonNegativeLongQueryParameter("afterSeq", "Return durable events after this non-negative sequence number.")
            positiveIntQueryParameter(
                name = "limit",
                defaultValue = DEFAULT_EVENT_LIMIT,
                description = "Requested replay size. Values above $MAX_EVENT_LIMIT are accepted and clamped to $MAX_EVENT_LIMIT.",
            )
        }
        responses {
            jsonResponse(
                status = HttpStatusCode.OK,
                description = "Canonical and replay-compatible durable events in sequence order.",
                schema = BackendEventOpenApiSchemas.replayResponse,
            )
            v1ErrorResponses(HttpStatusCode.BadRequest, HttpStatusCode.NotFound)
        }
    }

    publicClientSocket(BackendHttpRoutes.CHAT_WS_PATTERN, deps, singleChat = true)
    publicClientSocket(BackendHttpRoutes.WS, deps, singleChat = false)
}

internal fun AgentEventEnvelope.isPublicClientEvent(): Boolean =
    when (type) {
        AgentEventType.TOOL_CALL_STARTED -> payload is PublicToolCallStartedPayload
        AgentEventType.THREAD_COMPLETED,
        AgentEventType.THREAD_FAILED,
        AgentEventType.THREAD_CANCELLED -> true
        // Out-of-band cross-channel push (ru.souz.backend.channels), never part of any thread this
        // client started — ordinary in-thread messages always carry a non-null executionId and
        // stay filtered out here, exactly as before.
        AgentEventType.MESSAGE_CREATED -> executionId == null
        else -> false
    }
