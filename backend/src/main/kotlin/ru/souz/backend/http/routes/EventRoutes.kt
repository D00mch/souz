package ru.souz.backend.http.routes

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.hide
import io.ktor.server.websocket.WebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.ExperimentalKtorApi
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import ru.souz.backend.http.BackendHttpDependencies
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.http.BackendEventOpenApiSchemas
import ru.souz.backend.http.BackendOpenApiTags
import ru.souz.backend.http.BackendV1EventsResponse
import ru.souz.backend.http.DEFAULT_EVENT_LIMIT
import ru.souz.backend.http.MAX_EVENT_LIMIT
import ru.souz.backend.http.describeV1
import ru.souz.backend.http.invalidV1Request
import ru.souz.backend.http.jsonResponse
import ru.souz.backend.http.nonNegativeLongQueryParameter
import ru.souz.backend.http.positiveIntQueryParameter
import ru.souz.backend.http.queryNonNegativeLong
import ru.souz.backend.http.queryPositiveInt
import ru.souz.backend.http.requireChatId
import ru.souz.backend.http.requireUserIdFromTrustedProxy
import ru.souz.backend.http.requireV1Service
import ru.souz.backend.http.requireWsEventsEnabled
import ru.souz.backend.http.toDto
import ru.souz.backend.http.uuidPathParameter
import ru.souz.backend.http.v1ErrorResponses

@OptIn(ExperimentalKtorApi::class)
internal fun Route.eventRoutes(deps: BackendHttpDependencies) {
    get(BackendHttpRoutes.CHAT_EVENTS_PATTERN) {
        requireWsEventsEnabled(deps.featureFlags)
        val service = requireV1Service(deps.eventService, "Event")
        val limit = call.queryPositiveInt("limit", DEFAULT_EVENT_LIMIT, MAX_EVENT_LIMIT)
        call.respond(
            BackendV1EventsResponse(
                items = service.listByChat(
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
        description = "Replays canonical durable events for an owned chat. Returns 404 feature_disabled when events are disabled; live-only message.delta events are never included.",
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
                description = "Canonical durable events in sequence order.",
                schema = BackendEventOpenApiSchemas.replayResponse,
            )
            v1ErrorResponses(HttpStatusCode.BadRequest, HttpStatusCode.NotFound)
        }
    }

    // ignore!
    get(BackendHttpRoutes.CHAT_WS_PATTERN) {
        requireWsEventsEnabled(deps.featureFlags)
        throw invalidV1Request("WebSocket upgrade is required.")
    }.hide()

    // ignore!
    webSocket(BackendHttpRoutes.CHAT_WS_PATTERN) {
        if (!deps.featureFlags.wsEvents) {
            close(
                CloseReason(
                    CloseReason.Codes.TRY_AGAIN_LATER,
                    "WebSocket events feature is disabled.",
                )
            )
            return@webSocket
        }
        val service = requireV1Service(deps.eventService, "Event")
        val chatId = call.requireChatId()
        val afterSeq = call.queryNonNegativeLong("afterSeq")
        val stream = try {
            service.openStream(
                userId = call.requireUserIdFromTrustedProxy(),
                chatId = chatId,
                afterSeq = afterSeq,
            )
        } catch (e: Exception) {
            handleWebSocketOpenFailure(e)
            return@webSocket
        }

        var lastSeq = afterSeq ?: 0L
        try {
            stream.replay.forEach { event ->
                if (event.seq > lastSeq) {
                    send(Frame.Text(websocketEventMapper.writeValueAsString(event.toDto())))
                    lastSeq = event.seq
                }
            }
            for (event in stream.liveEvents) {
                val seq = event.seq
                if (!event.durable) {
                    send(Frame.Text(websocketEventMapper.writeValueAsString(event.toDto())))
                    continue
                }
                if (seq != null && seq > lastSeq) {
                    send(Frame.Text(websocketEventMapper.writeValueAsString(event.toDto())))
                    lastSeq = seq
                }
            }
        } finally {
            stream.close()
        }
    }.hide()
}

private suspend fun WebSocketServerSession.handleWebSocketOpenFailure(error: Exception) {
    if (error is ru.souz.backend.http.BackendV1Exception) {
        close(
            CloseReason(
                CloseReason.Codes.VIOLATED_POLICY,
                error.message,
            )
        )
        return
    }
    throw error
}

private val websocketEventMapper = jacksonObjectMapper().registerKotlinModule()
