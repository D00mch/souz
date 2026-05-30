package ru.souz.backend.http.routes

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.websocket.WebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import ru.souz.backend.http.BackendHttpDependencies
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.http.BackendV1EventsResponse
import ru.souz.backend.http.DEFAULT_EVENT_LIMIT
import ru.souz.backend.http.MAX_EVENT_LIMIT
import ru.souz.backend.http.invalidV1Request
import ru.souz.backend.http.queryNonNegativeLong
import ru.souz.backend.http.queryPositiveInt
import ru.souz.backend.http.requireChatId
import ru.souz.backend.http.requireUserIdFromTrustedProxy
import ru.souz.backend.http.requireV1Service
import ru.souz.backend.http.requireWsEventsEnabled
import ru.souz.backend.http.toDto

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
    }

    get(BackendHttpRoutes.CHAT_WS_PATTERN) {
        requireWsEventsEnabled(deps.featureFlags)
        throw invalidV1Request("WebSocket upgrade is required.")
    }

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
    }
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
