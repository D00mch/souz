package ru.souz.backend.http.routes

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.hide
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.ExperimentalKtorApi
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.time.Instant
import java.util.UUID
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.client.ChatCreateAck
import ru.souz.backend.client.ChatCreateFrame
import ru.souz.backend.client.ChatSubscribeAck
import ru.souz.backend.client.ChatSubscribeFrame
import ru.souz.backend.client.ClientContractException
import ru.souz.backend.client.ClientError
import ru.souz.backend.client.CreateClientChatRequest
import ru.souz.backend.client.HandledClientFrame
import ru.souz.backend.client.HistoryAppendAck
import ru.souz.backend.client.HistoryAppendFrame
import ru.souz.backend.client.MessageSubmitAck
import ru.souz.backend.client.MessageSubmitFrame
import ru.souz.backend.client.PublicClientService
import ru.souz.backend.client.ThreadCancelAck
import ru.souz.backend.client.ThreadCancelFrame
import ru.souz.backend.client.ToolResultAck
import ru.souz.backend.client.ToolResultFrame
import ru.souz.backend.client.supportedClientTypes
import ru.souz.backend.client.toStatusFrame
import ru.souz.backend.common.backendLogContext
import ru.souz.backend.common.withBackendLogContext
import ru.souz.backend.events.bus.AgentEventStream
import ru.souz.backend.events.model.AgentEvent
import ru.souz.backend.events.model.PublicToolCallStartedPayload
import ru.souz.backend.http.BackendHttpDependencies
import ru.souz.backend.http.BackendV1Exception
import ru.souz.backend.http.queryNonNegativeLong
import ru.souz.backend.http.requireChatId
import ru.souz.backend.http.toPublicDto

@OptIn(ExperimentalKtorApi::class, ExperimentalCoroutinesApi::class)
internal fun Route.publicClientSocket(path: String, deps: BackendHttpDependencies, singleChat: Boolean) {
    get(path) { call.respond(HttpStatusCode.BadRequest) }.hide()
    webSocket(path) {
        val socketId = UUID.randomUUID().toString()
        val clientType = call.request.queryParameters["clientType"]
        withContext(backendLogContext("socketId" to socketId, "clientType" to clientType)) {
            socketLogger.info("WebSocket connected route={}", path)
            try {
                if (!deps.featureFlags.wsEvents) {
                    socketLogger.warn("WebSocket rejected closeCode=1013 reason=feature_disabled")
                    close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "WebSocket feature is disabled."))
                    return@withContext
                }
                val allowedTypes = if (singleChat) supportedClientTypes else setOf("backend")
                if (clientType !in allowedTypes) {
                    socketLogger.warn("WebSocket rejected closeCode=1008 reason=invalid_client_type")
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "clientType must be ${allowedTypes.joinToString(" or ")}."))
                    return@withContext
                }
                val chat = try {
                    if (singleChat) deps.publicClientService.requireChat(call.requireChatId(), requireNotNull(clientType)) else null
                } catch (error: ClientContractException) {
                    socketLogger.warn("WebSocket rejected closeCode=1008 reason={}", error.code)
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, error.message))
                    return@withContext
                }
                val afterSeq = if (singleChat) call.queryNonNegativeLong("afterSeq") ?: 0L else 0L
                runClientSocket(deps, requireNotNull(clientType), chat, afterSeq, socketId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                socketLogger.error("WebSocket failed route=$path", failure)
                throw failure
            } finally {
                val reason = if (closeReason.isCompleted) runCatching { closeReason.getCompleted() }.getOrNull() else null
                socketLogger.info("WebSocket ended closeCode={} active={}", reason?.code, isActive)
            }
        }
    }.hide()
}

private suspend fun DefaultWebSocketServerSession.runClientSocket(
    deps: BackendHttpDependencies,
    clientType: String,
    boundChat: Chat?,
    afterSeq: Long,
    socketId: String,
) = coroutineScope {
    val service = deps.publicClientService
    val subscriptions = mutableMapOf<UUID, Job>()
    val sendMutex = Mutex()
    suspend fun writeJson(value: Any) = send(Frame.Text(publicWebSocketMapper.writeValueAsString(value)))
    fun subscribe(chat: Chat, stream: AgentEventStream): CompletableDeferred<Unit> {
        val replayDone = CompletableDeferred<Unit>()
        // Enter the cleanup block even if the connection is cancelled before the first dispatch.
        subscriptions[chat.id] = launch(
            backendLogContext("socketId" to socketId, "userId" to chat.userId, "chatId" to chat.id),
            start = CoroutineStart.UNDISPATCHED,
        ) {
            try {
                stream.forwardPublicEvents(replayDone) { event ->
                    withBackendLogContext(
                        "threadId" to event.executionId, "seq" to event.seq, "type" to event.type.value,
                        "toolCallId" to (event.payload as? PublicToolCallStartedPayload)?.toolCallId,
                    ) {
                        try {
                            sendMutex.withLock { writeJson(event.toPublicDto()) }
                            socketLogger.info("WebSocket event sent")
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Throwable) {
                            socketLogger.error("WebSocket event send failed", failure)
                            throw failure
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                socketLogger.error("WebSocket event stream failed", failure)
                throw failure
            } finally {
                withContext(NonCancellable) { stream.close() }
                socketLogger.info("WebSocket subscription closed")
            }
        }
        return replayDone
    }
    suspend fun prepare(chat: Chat, cursor: Long?): AgentEventStream? =
        if (chat.id in subscriptions) null else deps.eventService.openPublicStream(chat.userId, chat.id, cursor)

    try {
        boundChat?.let {
            withBackendLogContext("userId" to it.userId, "chatId" to it.id) {
                socketLogger.info("WebSocket initial replay starting afterSeq={}", afterSeq)
                subscribe(it, requireNotNull(prepare(it, afterSeq))).await()
                socketLogger.info("WebSocket initial replay finished")
            }
        }
        for (frame in incoming) {
            if (frame !is Frame.Text) {
                socketLogger.warn("WebSocket frame ignored frameType={}", frame.frameType)
                continue
            }
            val started = TimeSource.Monotonic.markNow()
            var stage = "decode"
            var logNode: JsonNode? = null
            var chat = boundChat
            var resolvedThreadId: UUID? = null
            fun context() = backendLogContext(
                "socketId" to socketId, "kind" to logNode?.get("kind")?.asText(),
                "clientRequestId" to logNode?.get("requestId")?.asText(),
                "userId" to chat?.userId,
                "chatId" to (chat?.id ?: logNode?.get("chatId")?.asText()),
                "threadId" to (resolvedThreadId ?: logNode?.get("threadId")?.asText()),
                "toolCallId" to logNode?.get("toolCallId")?.asText(),
            )
            var pendingStream: AgentEventStream? = null
            try {
                val node = parseClientFrame(frame.readText())
                logNode = node
                withContext(context()) {
                    val kind = node.path("kind").asText()
                    socketLogger.info("WebSocket frame received bytes={}", frame.data.size)
                    stage = "validate_kind"
                    if (kind !in clientFrameKinds || (boundChat != null && kind.startsWith("chat."))) {
                        throw InvalidClientFrameException("Unsupported frame kind.")
                    }
                    val handled = try {
                        when (kind) {
                            "chat.create" -> {
                                stage = "create_chat"
                                val create = decodeClientFrame(node, ChatCreateFrame::class.java)
                                val (created, duplicate) = deps.createClientChat(
                                    CreateClientChatRequest(create.payload.userId, create.requestId, clientType, create.payload.title)
                                )
                                chat = created
                                withContext(context()) {
                                    socketLogger.info("WebSocket chat ready duplicate={}", duplicate)
                                    stage = "prepare_subscription"
                                    pendingStream = prepare(created, null)
                                    HandledClientFrame(ChatCreateAck(
                                        userId = created.userId, requestId = created.requestId, chatId = created.id.toString(),
                                        status = "accepted", duplicate = duplicate, receivedAt = created.createdAt.toString(),
                                    ))
                                }
                            }
                            "chat.subscribe" -> {
                                stage = "resolve_chat"
                                val subscribe = decodeClientFrame(node, ChatSubscribeFrame::class.java)
                                requireSubscribeCursor(node)
                                val requestId = subscribe.requestId.trim().takeIf { it.isNotEmpty() }
                                    ?: throw ClientContractException("invalid_request", "requestId must not be empty.")
                                val target = service.resolveFrameChat(node, clientType, boundChat)
                                chat = target
                                withContext(context()) {
                                    stage = "prepare_subscription"
                                    pendingStream = if (node.has("afterSeq")) {
                                        deps.eventService.openPublicStream(target.userId, target.id, subscribe.afterSeq)
                                    } else prepare(target, subscribe.afterSeq)
                                    // Prepare first to cover concurrent events; stop the old sender before the replay ack.
                                    stage = "replace_subscription"
                                    if (pendingStream != null) subscriptions.remove(target.id)?.cancelAndJoin()
                                    HandledClientFrame(ChatSubscribeAck(
                                        chatId = target.id.toString(), requestId = requestId, status = "accepted",
                                        duplicate = pendingStream == null, receivedAt = Instant.now().toString(),
                                    ))
                                }
                            }
                            else -> {
                                stage = "resolve_chat"
                                val target = service.resolveFrameChat(node, clientType, boundChat)
                                chat = target
                                withContext(context()) {
                                    stage = "prepare_subscription"
                                    if (kind == "message.submit") pendingStream = prepare(target, null)
                                    stage = "handle_frame"
                                    handleChatFrame(service, target, node, kind)
                                }
                            }
                        }
                    } catch (error: ClientContractException) {
                        if (error.details != null) stage = "decode_frame"
                        withContext(context()) {
                            socketLogger.error(
                                "WebSocket frame rejected stage={} code={} details={}",
                                stage, error.code, error.details)
                        }
                        rejectedFor(node, boundChat?.id?.toString(), kind, error.code, error.message, error.details)
                    } catch (error: BackendV1Exception) {
                        withContext(context()) {
                            socketLogger.error("WebSocket frame rejected stage={} code={}", stage, error.code)
                        }
                        rejectedFor(node, boundChat?.id?.toString(), kind, error.code, error.message)
                    }
                    resolvedThreadId = handled.statusFeedback?.threadId
                    withContext(context()) {
                        pendingStream?.let {
                            socketLogger.info("WebSocket subscription prepared initialSeq={}", it.initialSeq)
                        }
                        stage = "wait_for_ack"
                        sendMutex.withLock {
                            stage = "send_ack"
                            writeJson(handled.response)
                            stage = "after_ack"
                            handled.afterSend()
                            socketLogger.info("WebSocket ack sent elapsedMs={}", started.elapsedNow().inWholeMilliseconds)
                            handled.statusFeedback?.let { feedback ->
                                stage = "send_status"
                                writeJson(service.threadStatus(requireNotNull(chat), feedback.threadId).toStatusFrame(feedback.requestId))
                            }
                        }
                        stage = "start_subscription"
                        if (kind != "message.submit" || handled.statusFeedback != null) pendingStream?.let { stream ->
                            subscribe(requireNotNull(chat), stream)
                            pendingStream = null
                        }
                    }
                }
            } catch (error: InvalidClientFrameException) {
                withContext(context()) { socketLogger.warn("WebSocket policy close stage={} closeCode=1008 reason={}", stage, error.message) }
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, error.message ?: "Invalid frame."))
                break
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                withContext(NonCancellable + context()) {
                    socketLogger.error("WebSocket frame failed stage=$stage elapsedMs=${started.elapsedNow().inWholeMilliseconds}", failure)
                }
                throw failure
            } finally {
                val interrupted = !isActive
                withContext(NonCancellable + context()) {
                    if (interrupted) socketLogger.info("WebSocket frame interrupted stage={} elapsedMs={}", stage, started.elapsedNow().inWholeMilliseconds)
                    pendingStream?.close?.invoke()
                }
            }
        }
    } finally {
        withContext(NonCancellable) {
            subscriptions.values.forEach { it.cancel() }
            subscriptions.values.toList().joinAll()
        }
    }
}

private suspend fun AgentEventStream.forwardPublicEvents(
    replayDone: CompletableDeferred<Unit>,
    send: suspend (AgentEvent) -> Unit,
) {
    var lastSeq = initialSeq
    suspend fun sendDurableEvents(events: Iterable<AgentEvent>) {
        events.forEach { event ->
            if (event.seq > lastSeq) {
                lastSeq = event.seq
                if (event.isPublicClientEvent()) send(event)
            }
        }
    }
    try {
        sendDurableEvents(replay)
        sendDurableEvents(replayAfter(lastSeq))
    } finally {
        replayDone.complete(Unit)
    }
    for (event in liveEvents) {
        val seq = event.seq
        if (seq == null || seq > lastSeq) sendDurableEvents(replayAfter(lastSeq))
    }
}

private suspend fun PublicClientService.resolveFrameChat(node: JsonNode, clientType: String, boundChat: Chat?): Chat {
    val id = runCatching { UUID.fromString(node.path("chatId").asText()) }.getOrNull()
        ?: throw ClientContractException("invalid_request", "chatId must be a UUID.")
    if (boundChat != null) {
        if (boundChat.id != id) throw ClientContractException("invalid_request", "Frame chatId does not match the socket.")
        return boundChat
    }
    return requireChat(id, clientType)
}

private suspend fun handleChatFrame(service: PublicClientService, chat: Chat, node: JsonNode, kind: String) =
    when (kind) {
        "message.submit" -> decodeClientFrame(node, MessageSubmitFrame::class.java).also {
            val capabilities = node.path("payload").path("device").path("capabilities")
            if (capabilities.isArray && capabilities.size() != capabilities.map(JsonNode::asText).distinct().size) {
                throw ClientContractException("invalid_request", "device.capabilities must be unique.")
            }
        }.let { service.handleMessage(chat, it) }
        "history.append" -> service.handleHistory(chat, decodeClientFrame(node, HistoryAppendFrame::class.java))
        "tool.result" -> service.handleToolResult(chat, decodeClientFrame(node, ToolResultFrame::class.java))
        "thread.cancel" -> service.handleCancel(chat, decodeClientFrame(node, ThreadCancelFrame::class.java))
        else -> throw InvalidClientFrameException("Unsupported frame kind.")
    }

private fun parseClientFrame(raw: String): JsonNode {
    val node = try {
        publicWebSocketMapper.readTree(raw) ?: throw InvalidClientFrameException("Frame must be valid JSON.")
    } catch (_: JsonProcessingException) {
        throw InvalidClientFrameException("Frame must be valid JSON.")
    }
    if (!node.isObject) throw InvalidClientFrameException("Frame must be a JSON object.")
    return node
}

private fun <T> decodeClientFrame(node: JsonNode, type: Class<T>): T = try {
    publicWebSocketMapper.treeToValue(node, type)
} catch (error: JsonProcessingException) {
    throw clientFrameDecodeError(node, error)
} catch (_: IllegalArgumentException) {
    throw ClientContractException("invalid_request", "Frame does not match the public contract.")
}

private fun requireSubscribeCursor(node: JsonNode) {
    node.get("afterSeq")?.let { cursor ->
        if (!cursor.isIntegralNumber || !cursor.canConvertToLong() || cursor.asLong() < 0) {
            throw ClientContractException("invalid_request", "afterSeq must be a non-negative integer.")
        }
    }
}

private fun rejectedFor(
    node: JsonNode, boundChatId: String?, kind: String, code: String, message: String, details: JsonNode? = null,
): HandledClientFrame {
    val now = Instant.now()
    val error = ClientError(code, message, details)
    val chatId = boundChatId ?: node.path("chatId").asText("")
    val requestId = node.path("requestId").asText("invalid")
    val threadId = node.path("threadId").asText("00000000-0000-0000-0000-000000000000")
    val response = when (kind) {
        "chat.create" -> ChatCreateAck(
            userId = node.path("payload").path("userId").takeIf { it.isTextual }?.asText(),
            requestId = requestId, chatId = null, status = "rejected", duplicate = false,
            error = error, receivedAt = now.toString(),
        )
        "chat.subscribe" -> ChatSubscribeAck(
            chatId = chatId, requestId = requestId, status = "rejected", duplicate = false,
            error = error, receivedAt = now.toString(),
        )
        "message.submit" -> MessageSubmitAck.rejected(chatId, requestId, error, now)
        "history.append" -> HistoryAppendAck.rejected(chatId, requestId, error, now)
        "tool.result" -> ToolResultAck.rejected(chatId, threadId, node.path("toolCallId").asText("invalid"), error, now)
        "thread.cancel" -> ThreadCancelAck.rejected(chatId, requestId, threadId, error, now)
        else -> throw InvalidClientFrameException("Unsupported frame kind.")
    }
    return HandledClientFrame(response)
}

private class InvalidClientFrameException(message: String) : RuntimeException(message)

private val socketLogger = LoggerFactory.getLogger("SouzClientWebSocket")
private val clientFrameKinds = setOf("chat.create", "chat.subscribe", "message.submit", "history.append", "tool.result", "thread.cancel")
private val publicWebSocketMapper = jacksonObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
