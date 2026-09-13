package ru.souz.backend.http.routes

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import java.time.Instant
import java.util.UUID
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
import ru.souz.backend.client.ThreadCancelAck
import ru.souz.backend.client.ThreadCancelFrame
import ru.souz.backend.client.ToolResultAck
import ru.souz.backend.client.ToolResultFrame
import ru.souz.backend.client.toStatusFrame
import ru.souz.backend.common.backendLogContext
import ru.souz.backend.common.withBackendLogContext
import ru.souz.backend.events.bus.AgentEventStream
import ru.souz.backend.events.model.AgentEvent
import ru.souz.backend.events.model.PublicToolCallStartedPayload
import ru.souz.backend.http.BackendHttpDependencies
import ru.souz.backend.http.BackendV1Exception
import ru.souz.backend.http.InvalidClientFrameException
import ru.souz.backend.http.decodeClientFrame
import ru.souz.backend.http.parseClient
import ru.souz.backend.http.sendClient
import ru.souz.backend.http.toPublicDto

internal class PublicClientConnection(
    private val socket: DefaultWebSocketServerSession,
    private val deps: BackendHttpDependencies,
    private val clientType: String,
    private val boundChat: Chat?,
    private val socketId: String,
) {
    private val service = deps.publicClientService
    private val subscriptions = mutableMapOf<UUID, Job>()
    private val sendMutex = Mutex()

    suspend fun run(afterSeq: Long) = coroutineScope {
        try {
            if (boundChat != null) {
                withBackendLogContext("userId" to boundChat.userId, "chatId" to boundChat.id) {
                    socketLogger.info("WebSocket initial replay starting afterSeq={}", afterSeq)
                    subscribe(this@coroutineScope, boundChat, requireNotNull(prepare(boundChat, afterSeq))).await()
                    socketLogger.info("WebSocket initial replay finished")
                }
            }
            for (frame in socket.incoming) {
                if (frame is Frame.Text) {
                    if (!processFrame(frame, this)) break
                } else {
                    socketLogger.warn("WebSocket frame ignored frameType={}", frame.frameType)
                }
            }
        } finally {
            withContext(NonCancellable) {
                subscriptions.values.forEach { it.cancel() }
                subscriptions.values.toList().joinAll()
            }
        }
    }

    private suspend fun processFrame(frame: Frame.Text, scope: CoroutineScope): Boolean {
        val started = TimeSource.Monotonic.markNow()
        var stage = "decode"
        var logNode: JsonNode? = null
        var chat = boundChat
        var resolvedThreadId: UUID? = null
        fun mdcContext() = backendLogContext(
            "socketId" to socketId, "kind" to logNode?.get("kind")?.asText(),
            "clientRequestId" to logNode?.get("requestId")?.asText(),
            "userId" to chat?.userId,
            "chatId" to (chat?.id ?: logNode?.get("chatId")?.asText()),
            "threadId" to (resolvedThreadId ?: logNode?.get("threadId")?.asText()),
            "toolCallId" to logNode?.get("toolCallId")?.asText(),
        )
        var pendingStream: AgentEventStream? = null
        try {
            val node = frame.parseClient()
            logNode = node
            fun <T> decode(type: Class<T>, nextStage: String = "handle_frame"): T {
                stage = "decode_frame"
                return node.decodeClientFrame(type).also { stage = nextStage }
            }
            withContext(mdcContext()) {
                val kind = node.path("kind").asText()
                socketLogger.info("WebSocket frame received bytes={}", frame.data.size)
                stage = "validate_kind"
                if (kind !in clientFrameKinds || (boundChat != null && kind.startsWith("chat."))) {
                    throw InvalidClientFrameException("Unsupported frame kind.")
                }
                @Suppress("SuspendFunSwallowedCancellation") // The error translation rethrows every other exception.
                val handled = try {
                    when (kind) {
                        "chat.create" -> {
                            val create = decode(ChatCreateFrame::class.java, "create_chat")
                            val (created, duplicate) = deps.createClientChat(
                                CreateClientChatRequest(create.payload.userId, create.requestId, clientType, create.payload.title)
                            )
                            chat = created
                            withContext(mdcContext()) {
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
                            val subscribe = decode(ChatSubscribeFrame::class.java, "resolve_chat")
                            node.requireSubscribe(subscribe)
                            val target = resolveFrameChat(node)
                            chat = target
                            withContext(mdcContext()) {
                                stage = "prepare_subscription"
                                pendingStream = if (node.has("afterSeq")) {
                                    deps.eventService.openPublicStream(target.userId, target.id, subscribe.afterSeq)
                                } else prepare(target, subscribe.afterSeq)
                                // Prepare first to cover concurrent events; stop the old sender before the replay ack.
                                stage = "replace_subscription"
                                if (pendingStream != null) subscriptions.remove(target.id)?.cancelAndJoin()
                                HandledClientFrame(ChatSubscribeAck(
                                    chatId = target.id.toString(), requestId = subscribe.requestId.trim(), status = "accepted",
                                    duplicate = pendingStream == null, receivedAt = Instant.now().toString(),
                                ))
                            }
                        }
                        else -> {
                            stage = "resolve_chat"
                            val target = resolveFrameChat(node)
                            chat = target
                            withContext(mdcContext()) {
                                stage = "prepare_subscription"
                                if (kind == "message.submit") pendingStream = prepare(target, null)
                                when (kind) {
                                    "message.submit" -> decode(MessageSubmitFrame::class.java).also {
                                        val capabilities = node.path("payload").path("device").path("capabilities")
                                        if (capabilities.isArray && capabilities.size() != capabilities.map(JsonNode::asText).distinct().size) {
                                            throw ClientContractException("invalid_request", "device.capabilities must be unique.")
                                        }
                                    }.let { service.handleMessage(target, it) }
                                    "history.append" -> service.handleHistory(target, decode(HistoryAppendFrame::class.java))
                                    "tool.result" -> service.handleToolResult(target, decode(ToolResultFrame::class.java))
                                    "thread.cancel" -> service.handleCancel(target, decode(ThreadCancelFrame::class.java))
                                    else -> throw InvalidClientFrameException("Unsupported frame kind.")
                                }
                            }
                        }
                    }
                } catch (failure: Exception) {
                    val error = when (failure) {
                        is ClientContractException -> ClientError(failure.code, failure.message, failure.details)
                        is BackendV1Exception -> ClientError(failure.code, failure.message)
                        else -> throw failure // cancellation will be rethrowed here
                    }
                    withContext(mdcContext()) {
                        socketLogger.error(
                            "WebSocket frame rejected stage={} code={} details={}",
                            stage, error.code, error.details)
                    }
                    rejectedFor(node, kind, error)
                }
                resolvedThreadId = handled.statusFeedback?.threadId
                withContext(mdcContext()) {
                    pendingStream?.let {
                        socketLogger.info("WebSocket subscription prepared initialSeq={}", it.initialSeq)
                    }
                    stage = "wait_for_ack"
                    sendMutex.withLock {
                        stage = "send_ack"
                        socket.sendClient(handled.response)
                        stage = "after_ack"
                        handled.afterSend()
                        socketLogger.info("WebSocket ack sent elapsedMs={}", started.elapsedNow().inWholeMilliseconds)
                        handled.statusFeedback?.let { feedback ->
                            stage = "send_status"
                            socket.sendClient(service.threadStatus(requireNotNull(chat), feedback.threadId).toStatusFrame(feedback.requestId))
                        }
                    }
                    stage = "start_subscription"
                    if (kind != "message.submit" || handled.statusFeedback != null) pendingStream?.let { stream ->
                        subscribe(scope, requireNotNull(chat), stream)
                        pendingStream = null
                    }
                }
            }
        } catch (error: InvalidClientFrameException) {
            withContext(mdcContext()) { socketLogger.warn("WebSocket policy close stage={} closeCode=1008 reason={}", stage, error.message) }
            socket.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, error.message ?: "Invalid frame."))
            return false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            withContext(NonCancellable + mdcContext()) {
                socketLogger.error("WebSocket frame failed stage=$stage elapsedMs=${started.elapsedNow().inWholeMilliseconds}", failure)
            }
            throw failure
        } finally {
            val interrupted = !scope.isActive
            withContext(NonCancellable + mdcContext()) {
                if (interrupted) socketLogger.info("WebSocket frame interrupted stage={} elapsedMs={}", stage, started.elapsedNow().inWholeMilliseconds)
                pendingStream?.close?.invoke()
            }
        }
        return true
    }

    private fun subscribe(scope: CoroutineScope, chat: Chat, stream: AgentEventStream): CompletableDeferred<Unit> {
        val replayDone = CompletableDeferred<Unit>()
        // Enter the cleanup block even if the connection is cancelled before the first dispatch.
        subscriptions[chat.id] = scope.launch(
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
                            sendMutex.withLock { socket.sendClient(event.toPublicDto()) }
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

    private suspend fun prepare(chat: Chat, cursor: Long?): AgentEventStream? =
        if (chat.id in subscriptions) null else deps.eventService.openPublicStream(chat.userId, chat.id, cursor)

    private suspend fun resolveFrameChat(node: JsonNode): Chat {
        val id = runCatching { UUID.fromString(node.path("chatId").asText()) }.getOrNull()
            ?: throw ClientContractException("invalid_request", "chatId must be a UUID.")
        if (boundChat != null) {
            if (boundChat.id != id) throw ClientContractException("invalid_request", "Frame chatId does not match the socket.")
            return boundChat
        }
        return service.requireChat(id, clientType)
    }

    private fun JsonNode.requireSubscribe(frame: ChatSubscribeFrame) {
        get("afterSeq")?.let { cursor ->
            if (!cursor.isIntegralNumber || !cursor.canConvertToLong() || cursor.asLong() < 0) {
                throw ClientContractException("invalid_request", "afterSeq must be a non-negative integer.")
            }
        }
        if (frame.requestId.isBlank()) throw ClientContractException("invalid_request", "requestId must not be empty.")
    }

    private fun rejectedFor(node: JsonNode, kind: String, error: ClientError): HandledClientFrame {
        val now = Instant.now()
        val chatId = boundChat?.id?.toString() ?: node.path("chatId").asText("")
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

private val socketLogger = LoggerFactory.getLogger("SouzClientWebSocket")
private val clientFrameKinds = setOf("chat.create", "chat.subscribe", "message.submit", "history.append", "tool.result", "thread.cancel")
