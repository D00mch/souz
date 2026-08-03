package ru.souz.backend.http.routes

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
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
import io.ktor.websocket.readText
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.backend.client.ClientContractException
import ru.souz.backend.client.ClientError
import ru.souz.backend.client.HandledClientFrame
import ru.souz.backend.client.MessageSubmitFrame
import ru.souz.backend.client.PublicClientService
import ru.souz.backend.client.RejectedMessageAck
import ru.souz.backend.client.ThreadCancelAck
import ru.souz.backend.client.ThreadCancelFrame
import ru.souz.backend.client.ToolResultAck
import ru.souz.backend.client.ToolResultFrame
import ru.souz.backend.client.supportedClientTypes
import ru.souz.backend.client.toStatusFrame
import ru.souz.backend.events.model.AgentEvent
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
import ru.souz.backend.http.requireV1Service
import ru.souz.backend.http.requireWsEventsEnabled
import ru.souz.backend.http.toDto
import ru.souz.backend.http.toPublicDto
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

    get(BackendHttpRoutes.CHAT_WS_PATTERN) {
        call.respond(HttpStatusCode.BadRequest)
    }.hide()

    webSocket(BackendHttpRoutes.CHAT_WS_PATTERN) {
        if (!deps.featureFlags.wsEvents) {
            close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "WebSocket feature is disabled."))
            return@webSocket
        }
        val clientService = requireV1Service(deps.publicClientService, "Public client")
        val eventService = requireV1Service(deps.eventService, "Event")
        val chatId = call.requireChatId()
        val clientType = call.request.queryParameters["clientType"]
        if (clientType !in supportedClientTypes) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "clientType must be backend or mobile_app."))
            return@webSocket
        }
        val afterSeq = call.queryNonNegativeLong("afterSeq") ?: 0L
        val chat = try {
            clientService.requireChat(chatId, requireNotNull(clientType))
        } catch (error: ClientContractException) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, error.message))
            return@webSocket
        }
        val stream = eventService.openPublicStream(chat.userId, chat.id, afterSeq)
        val sendMutex = Mutex()
        suspend fun writeJson(value: Any) {
            send(Frame.Text(publicWebSocketMapper.writeValueAsString(value)))
        }
        suspend fun sendJson(value: Any) {
            sendMutex.withLock { writeJson(value) }
        }
        suspend fun sendHandledFrame(handled: HandledClientFrame) {
            sendMutex.withLock {
                writeJson(handled.response)
                handled.afterSend()
                sendStatusFeedback(clientService, chat, handled.response, ::writeJson)
            }
        }

        try {
            coroutineScope {
                val replayDone = CompletableDeferred<Unit>()
                val sender = launch {
                    var lastSeq = afterSeq
                    suspend fun sendDurableEvents(events: Iterable<AgentEvent>) {
                        events.forEach { event ->
                            lastSeq = maxOf(lastSeq, event.seq)
                            if (event.isPublicClientEvent()) sendJson(event.toPublicDto())
                        }
                    }
                    try {
                        sendDurableEvents(stream.replay)
                        sendDurableEvents(stream.replayAfter(lastSeq))
                    } finally {
                        replayDone.complete(Unit)
                    }
                    for (event in stream.liveEvents) {
                        val seq = event.seq
                        if (seq == null || seq > lastSeq) {
                            sendDurableEvents(stream.replayAfter(lastSeq))
                        }
                    }
                }
                try {
                    replayDone.await()
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val handled = try {
                            handleClientFrame(clientService, chat, frame.readText())
                        } catch (error: InvalidClientFrameException) {
                            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, error.message ?: "Invalid frame."))
                            break
                        }
                        sendHandledFrame(handled)
                    }
                } finally {
                    sender.cancelAndJoin()
                }
            }
        } finally {
            stream.close()
        }
    }.hide()
}

private suspend fun handleClientFrame(
    service: PublicClientService,
    chat: ru.souz.backend.chat.model.Chat,
    raw: String,
): HandledClientFrame {
    val node = try {
        publicWebSocketMapper.readTree(raw) ?: throw InvalidClientFrameException("Frame must be valid JSON.")
    } catch (_: JsonProcessingException) {
        throw InvalidClientFrameException("Frame must be valid JSON.")
    }
    if (!node.isObject) throw InvalidClientFrameException("Frame must be a JSON object.")
    val kind = node.path("kind").asText()
    return try {
        when (kind) {
            "message.submit" -> decodeClientFrame(node, MessageSubmitFrame::class.java).also {
                requireFrameChat(chat.id, it.chatId)
                val capabilities = node.path("payload").path("device").path("capabilities")
                if (capabilities.isArray && capabilities.size() != capabilities.map(JsonNode::asText).distinct().size) {
                    throw ClientContractException("invalid_request", "device.capabilities must be unique.")
                }
            }.let { service.handleMessage(chat, it) }

            "tool.result" -> decodeClientFrame(node, ToolResultFrame::class.java).also {
                requireFrameChat(chat.id, it.chatId)
            }.let { service.handleToolResult(chat, it) }

            "thread.cancel" -> decodeClientFrame(node, ThreadCancelFrame::class.java).also {
                requireFrameChat(chat.id, it.chatId)
            }.let { service.handleCancel(chat, it) }

            else -> throw InvalidClientFrameException("Unsupported frame kind.")
        }
    } catch (error: ClientContractException) {
        rejectedFor(node, chat.id, kind, error.code, error.message)
    }
}

private suspend fun sendStatusFeedback(
    service: PublicClientService,
    chat: ru.souz.backend.chat.model.Chat,
    response: Any,
    sendJson: suspend (Any) -> Unit,
) {
    val requestAndThread = when (response) {
        is ru.souz.backend.client.AcceptedMessageAck ->
            response.requestId to runCatching { UUID.fromString(response.thread.id) }.getOrNull()
        is ThreadCancelAck ->
            response.requestId.takeIf { response.status == "accepted" } to
                runCatching { UUID.fromString(response.threadId) }.getOrNull()
        else -> null to null
    }
    val requestId = requestAndThread.first ?: return
    val threadId = requestAndThread.second ?: return
    val status = runCatching { service.threadStatus(chat, threadId).toStatusFrame(requestId) }.getOrNull() ?: return
    sendJson(status)
}

private fun <T> decodeClientFrame(node: JsonNode, type: Class<T>): T = try {
    publicWebSocketMapper.treeToValue(node, type)
} catch (_: JsonProcessingException) {
    throw ClientContractException("invalid_request", "Frame does not match the public contract.")
} catch (_: IllegalArgumentException) {
    throw ClientContractException("invalid_request", "Frame does not match the public contract.")
}

private fun requireFrameChat(expected: UUID, raw: String) {
    if (runCatching { UUID.fromString(raw) }.getOrNull() != expected) {
        throw ClientContractException("invalid_request", "Frame chatId does not match the socket.")
    }
}

private fun rejectedFor(
    node: JsonNode,
    chatId: UUID,
    kind: String,
    code: String,
    message: String,
): HandledClientFrame {
    val now = Instant.now().toString()
    val error = ClientError(code, message)
    return when (kind) {
        "message.submit" -> HandledClientFrame(
            RejectedMessageAck(
                chatId = chatId.toString(),
                requestId = node.path("requestId").asText("invalid"),
                error = error,
                receivedAt = now,
            )
        )
        "tool.result" -> HandledClientFrame(
            ToolResultAck(
                chatId = chatId.toString(),
                toolCallId = node.path("toolCallId").asText("invalid"),
                threadId = node.path("threadId").asText("00000000-0000-0000-0000-000000000000"),
                status = "rejected",
                duplicate = false,
                error = error,
                receivedAt = now,
            )
        )
        "thread.cancel" -> HandledClientFrame(
            ThreadCancelAck(
                chatId = chatId.toString(),
                requestId = node.path("requestId").asText("invalid"),
                threadId = node.path("threadId").asText("00000000-0000-0000-0000-000000000000"),
                status = "rejected",
                duplicate = false,
                error = error,
                receivedAt = now,
            )
        )
        else -> throw InvalidClientFrameException("Unsupported frame kind.")
    }
}

private fun AgentEventEnvelope.isPublicClientEvent(): Boolean =
    when (type) {
        AgentEventType.TOOL_CALL_STARTED -> payload is PublicToolCallStartedPayload
        AgentEventType.THREAD_COMPLETED,
        AgentEventType.THREAD_FAILED,
        AgentEventType.THREAD_CANCELLED -> true
        else -> false
    }

private class InvalidClientFrameException(message: String) : RuntimeException(message)

private val publicWebSocketMapper = jacksonObjectMapper()
    .registerKotlinModule()
    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
