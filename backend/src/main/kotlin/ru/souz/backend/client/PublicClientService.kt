package ru.souz.backend.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.withTimeoutOrNull
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.chat.model.ChatRole
import ru.souz.backend.chat.repository.ChatRepository
import ru.souz.backend.chat.repository.MessageRepository
import ru.souz.backend.client.model.ClientRequest
import ru.souz.backend.client.repository.ClientRequestRepository
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.execution.repository.AgentExecutionRepository
import ru.souz.backend.execution.service.AgentExecutionService
import ru.souz.backend.settings.service.UserSettingsOverrides
import ru.souz.backend.toolcall.model.ToolCallStatus
import ru.souz.backend.toolcall.repository.ToolCallContext
import ru.souz.backend.toolcall.repository.ToolCallRepository

internal data class HandledClientFrame(
    val response: Any,
    val afterSend: suspend () -> Unit = {},
)

internal class PublicClientService(
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val executionRepository: AgentExecutionRepository,
    private val clientRequestRepository: ClientRequestRepository,
    private val toolCallRepository: ToolCallRepository,
    private val executionService: AgentExecutionService,
    private val registry: ClientThreadRuntimeRegistry,
    private val mapper: ObjectMapper = jacksonObjectMapper().registerKotlinModule(),
) {
    suspend fun requireChat(chatId: UUID, clientType: String): Chat {
        val chat = chatRepository.getById(chatId) ?: throw ClientContractException("chat_not_found", "Chat not found.")
        if (chat.clientType != clientType) {
            throw ClientContractException("invalid_request", "clientType does not match the chat.")
        }
        return chat
    }

    suspend fun handleMessage(chat: Chat, frame: MessageSubmitFrame): HandledClientFrame {
        val now = Instant.now()
        val requestId = frame.requestId.required("requestId")
        validateDevice(chat, frame.payload.device)
        validateContent(frame.payload.content)
        val threadId = frame.threadId?.uuid("threadId")
        val payloadHash = PublicPayloadHash.ofValue(
            linkedMapOf(
                "kind" to frame.kind,
                "threadId" to threadId?.toString(),
                "device" to frame.payload.device.copy(capabilities = frame.payload.device.capabilities.toSortedSet()),
                "content" to frame.payload.content,
                "meta" to frame.payload.meta,
            )
        )
        clientRequestRepository.get(chat.id, requestId)?.let { stored ->
            if (stored.kind != frame.kind || stored.payloadHash != payloadHash) {
                return rejectedMessage(chat.id, requestId, "idempotency_conflict", "requestId was used with a different payload.", now)
            }
            val original = mapper.readValue(stored.ackJson, AcceptedMessageAck::class.java)
            return HandledClientFrame(original.copy(duplicate = true)) {
                registry.ackSent(UUID.fromString(original.thread.id), requestId)
            }
        }
        return if (threadId == null) {
            startThread(chat, frame, requestId, payloadHash, now)
        } else {
            continueThread(chat, frame, threadId, requestId, payloadHash, now)
        }
    }

    suspend fun handleToolResult(chat: Chat, frame: ToolResultFrame): HandledClientFrame {
        val now = Instant.now()
        val threadId = frame.threadId.uuid("threadId")
        val toolCallId = frame.toolCallId.required("toolCallId")
        val status = frame.status.takeIf { it in supportedToolResultStatuses }
            ?: return rejectedTool(chat.id, threadId, toolCallId, "invalid_request", "Unsupported tool result status.", now)
        if (status == "succeeded" && frame.result == null) {
            return rejectedTool(chat.id, threadId, toolCallId, "invalid_request", "A succeeded result requires result.", now)
        }
        if (status == "succeeded" && frame.error != null) {
            return rejectedTool(chat.id, threadId, toolCallId, "invalid_request", "A succeeded result must not include error.", now)
        }
        if (status != "succeeded" && frame.error == null) {
            return rejectedTool(chat.id, threadId, toolCallId, "invalid_request", "A non-succeeded result requires error.", now)
        }
        if (status != "succeeded" && frame.result != null) {
            return rejectedTool(chat.id, threadId, toolCallId, "invalid_request", "A non-succeeded result must not include result.", now)
        }
        val context = ToolCallContext(chat.userId, chat.id.toString(), threadId.toString(), toolCallId)
        val existing = toolCallRepository.get(context)
            ?: return rejectedTool(chat.id, threadId, toolCallId, "tool_call_not_found", "Tool call not found.", now)
        if (existing.target != "client") {
            return rejectedTool(chat.id, threadId, toolCallId, "tool_call_not_found", "Client tool call not found.", now)
        }
        val payloadHash = PublicPayloadHash.ofJson(
            mapper.valueToTree(linkedMapOf("status" to status, "result" to frame.result, "error" to frame.error))
        )
        if (existing.status != ToolCallStatus.RUNNING) {
            return if (existing.resultPayloadHash == payloadHash) {
                val outcome = existing.toClientToolOutcome()
                HandledClientFrame(acceptedTool(chat.id, threadId, toolCallId, duplicate = true, now)) {
                    registry.finishTool(threadId, toolCallId, outcome)
                }
            } else {
                rejectedTool(chat.id, threadId, toolCallId, "idempotency_conflict", "toolCallId already has a different terminal result.", now)
            }
        }
        val execution = executionRepository.getByChat(chat.userId, chat.id, threadId)
        if (execution == null || !execution.status.isRunningThread() || !registry.contains(threadId)) {
            return rejectedTool(chat.id, threadId, toolCallId, "thread_already_terminal", "Thread is not running.", now)
        }
        val completed = toolCallRepository.completeClientCall(
            context = context,
            status = status.toToolCallStatus(),
            resultJson = frame.result?.let(mapper::writeValueAsString),
            errorJson = frame.error?.let(mapper::writeValueAsString),
            payloadHash = payloadHash,
            receivedAt = now,
        ) ?: return rejectedTool(chat.id, threadId, toolCallId, "message_rejected", "Tool call is no longer pending.", now)
        val outcome = ClientToolOutcome(completed.status.value, frame.result, frame.error)
        return HandledClientFrame(
            response = acceptedTool(chat.id, threadId, toolCallId, duplicate = false, now),
            afterSend = { registry.finishTool(threadId, toolCallId, outcome) },
        )
    }

    suspend fun handleCancel(chat: Chat, frame: ThreadCancelFrame): HandledClientFrame {
        val now = Instant.now()
        val requestId = frame.requestId.required("requestId")
        val threadId = frame.threadId.uuid("threadId")
        if (frame.reason != null && frame.reason !in supportedCancelReasons) {
            return rejectedCancel(chat.id, requestId, threadId, "invalid_request", "Unsupported cancellation reason.", now)
        }
        val payloadHash = PublicPayloadHash.ofValue(
            linkedMapOf("kind" to frame.kind, "threadId" to threadId.toString(), "reason" to frame.reason)
        )
        clientRequestRepository.get(chat.id, requestId)?.let { stored ->
            if (stored.kind != frame.kind || stored.payloadHash != payloadHash) {
                return rejectedCancel(chat.id, requestId, threadId, "idempotency_conflict", "requestId was used with a different payload.", now)
            }
            val original = mapper.readValue(stored.ackJson, ThreadCancelAck::class.java)
            return HandledClientFrame(original.copy(duplicate = true)) {
                registry.ackSent(threadId, requestId)
            }
        }
        val execution = executionRepository.getByChat(chat.userId, chat.id, threadId)
            ?: return rejectedCancel(chat.id, requestId, threadId, "thread_not_found", "Thread not found.", now)
        if (!execution.status.isRunningThread()) {
            return rejectedCancel(chat.id, requestId, threadId, "thread_already_terminal", "Thread is already terminal.", now)
        }
        val cancelled = registry.acceptCancellation(
            threadId = threadId,
            requestId = requestId,
            canAccept = {
                executionRepository.getByChat(chat.userId, chat.id, threadId)?.status?.isRunningThread() == true
            },
            commit = { executionService.cancelExecution(chat.userId, chat.id, threadId) },
        )
        if (cancelled == null) {
            val latest = executionRepository.getByChat(chat.userId, chat.id, threadId)
            return if (latest != null && !latest.status.isRunningThread()) {
                rejectedCancel(chat.id, requestId, threadId, "thread_already_terminal", "Thread is already terminal.", now)
            } else {
                rejectedCancel(chat.id, requestId, threadId, "message_rejected", "Live thread state is unavailable.", now)
            }
        }
        val ack = ThreadCancelAck(
            chatId = chat.id.toString(),
            requestId = requestId,
            threadId = threadId.toString(),
            status = "accepted",
            duplicate = false,
            error = null,
            receivedAt = now.toString(),
        )
        clientRequestRepository.create(
            ClientRequest(chat.id, requestId, frame.kind, threadId, payloadHash, mapper.writeValueAsString(ack), now)
        )
        return HandledClientFrame(ack) { registry.ackSent(threadId, requestId) }
    }

    private suspend fun startThread(
        chat: Chat,
        frame: MessageSubmitFrame,
        requestId: String,
        payloadHash: String,
        now: Instant,
    ): HandledClientFrame {
        if (executionRepository.findActive(chat.userId, chat.id) != null) {
            return rejectedMessage(chat.id, requestId, "message_rejected", "The chat already has a running thread.", now)
        }
        val threadId = UUID.randomUUID()
        registry.register(threadId, frame.payload.device)
        registry.registerAck(threadId, requestId)
        val deviceJson = mapper.writeValueAsString(frame.payload.device)
        val metadata = inputMetadata(frame, inputSeq = 1)
        val overrides = requestOverrides(frame.payload.meta)
        val result = try {
            executionService.executeChatTurn(
                userId = chat.userId,
                chatId = chat.id,
                content = frame.payload.content.text,
                clientMessageId = requestId,
                requestOverrides = overrides,
                executionId = threadId,
                revision = 1,
                latestDeviceContextJson = deviceJson,
                userMessageMetadata = metadata,
                clientToolsEnabled = true,
                forceBackground = true,
            )
        } catch (error: Exception) {
            registry.ackSent(threadId, requestId)
            return rejectedMessage(chat.id, requestId, "message_rejected", error.message ?: "Message was rejected.", now)
        }
        val ack = AcceptedMessageAck(
            chatId = chat.id.toString(),
            requestId = requestId,
            duplicate = false,
            submission = SubmissionAck(1),
            thread = ThreadAck(result.execution.id.toString(), created = true, revision = 1),
            receivedAt = now.toString(),
        )
        clientRequestRepository.create(
            ClientRequest(chat.id, requestId, frame.kind, threadId, payloadHash, mapper.writeValueAsString(ack), now)
        )
        return HandledClientFrame(ack) { registry.ackSent(threadId, requestId) }
    }

    private suspend fun continueThread(
        chat: Chat,
        frame: MessageSubmitFrame,
        threadId: UUID,
        requestId: String,
        payloadHash: String,
        now: Instant,
    ): HandledClientFrame {
        val execution = executionRepository.getByChat(chat.userId, chat.id, threadId)
            ?: return rejectedMessage(chat.id, requestId, "thread_not_found", "Thread not found.", now)
        if (!execution.status.isRunningThread()) {
            return rejectedMessage(chat.id, requestId, "thread_already_terminal", "Thread is already terminal.", now)
        }
        var activeExecution: ru.souz.backend.execution.model.AgentExecution? = null
        val inputSeq = withTimeoutOrNull(5_000) {
            registry.acceptInput(
                threadId = threadId,
                requestId = requestId,
                device = frame.payload.device,
                input = frame.payload.content.text,
                canAccept = {
                    executionRepository.getByChat(chat.userId, chat.id, threadId)
                        ?.takeIf { it.status.isRunningThread() }
                        ?.also { activeExecution = it } != null
                },
                commit = {
                    val current = requireNotNull(activeExecution)
                    val nextInputSeq = current.revision + 1
                    messageRepository.append(
                        userId = chat.userId,
                        chatId = chat.id,
                        role = ChatRole.USER,
                        content = frame.payload.content.text,
                        metadata = inputMetadata(frame, nextInputSeq),
                    )
                    executionRepository.update(
                        current.copy(
                            revision = nextInputSeq,
                            latestDeviceContextJson = mapper.writeValueAsString(frame.payload.device),
                        )
                    )
                    nextInputSeq
                },
            )
        }
        if (inputSeq == null) {
            val latest = executionRepository.getByChat(chat.userId, chat.id, threadId)
            val code = if (latest != null && !latest.status.isRunningThread()) {
                "thread_already_terminal"
            } else {
                "message_rejected"
            }
            val message = if (code == "thread_already_terminal") "Thread is already terminal." else "Thread no longer accepts input."
            return rejectedMessage(chat.id, requestId, code, message, now)
        }
        val ack = AcceptedMessageAck(
            chatId = chat.id.toString(),
            requestId = requestId,
            duplicate = false,
            submission = SubmissionAck(inputSeq),
            thread = ThreadAck(threadId.toString(), created = false, revision = inputSeq),
            receivedAt = now.toString(),
        )
        clientRequestRepository.create(
            ClientRequest(chat.id, requestId, frame.kind, threadId, payloadHash, mapper.writeValueAsString(ack), now)
        )
        return HandledClientFrame(ack) { registry.ackSent(threadId, requestId) }
    }

    private fun inputMetadata(frame: MessageSubmitFrame, inputSeq: Long): Map<String, String> = buildMap {
        put("inputSeq", inputSeq.toString())
        put("source", frame.payload.content.source)
        put("device", mapper.writeValueAsString(frame.payload.device))
        put("requestId", frame.requestId)
        frame.payload.meta?.let { put("requestMeta", mapper.writeValueAsString(it)) }
    }

    private fun requestOverrides(meta: ClientRequestMeta?): UserSettingsOverrides = UserSettingsOverrides(
        locale = meta?.locale?.let { Locale.forLanguageTag(it).takeIf { locale -> locale.language.isNotBlank() } }
            ?: Locale.forLanguageTag("ru-RU"),
        timeZone = meta?.timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault(),
        streamingMessages = true,
    )

    private fun ru.souz.backend.toolcall.model.ToolCall.toClientToolOutcome(): ClientToolOutcome =
        ClientToolOutcome(
            status = status.value,
            result = resultJson?.let { mapper.readTree(it) },
            error = errorJson?.let { stored ->
                runCatching { mapper.readValue(stored, ClientError::class.java) }
                    .getOrElse { ClientError("client_tool_failed", "Client tool failed.") }
            },
        )

    private fun validateDevice(chat: Chat, device: ClientDevice) {
        if (device.userId.uuid("device.userId").toString() != chat.userId) {
            throw ClientContractException("invalid_request", "device.userId does not own the chat.")
        }
        device.deviceId.required("device.deviceId")
        if (device.deviceType !in supportedDeviceTypes) throw ClientContractException("invalid_request", "Unsupported deviceType.")
        if (!supportedDeviceCapabilities.containsAll(device.capabilities)) {
            throw ClientContractException("invalid_request", "Unsupported device capability.")
        }
    }

    private fun validateContent(content: RecognizedTextContent) {
        if (content.type != "text") throw ClientContractException("invalid_request", "content.type must be text.")
        if (content.source != "voice" && content.source != "text") {
            throw ClientContractException("invalid_request", "content.source must be voice or text.")
        }
        content.text.required("content.text")
    }

    private fun rejectedMessage(chatId: UUID, requestId: String, code: String, message: String, now: Instant) =
        HandledClientFrame(
            RejectedMessageAck(
                chatId = chatId.toString(), requestId = requestId, error = ClientError(code, message), receivedAt = now.toString()
            )
        )

    private fun acceptedTool(chatId: UUID, threadId: UUID, toolCallId: String, duplicate: Boolean, now: Instant) =
        ToolResultAck(
            chatId = chatId.toString(), toolCallId = toolCallId, threadId = threadId.toString(),
            status = "accepted", duplicate = duplicate, error = null, receivedAt = now.toString(),
        )

    private fun rejectedTool(chatId: UUID, threadId: UUID, toolCallId: String, code: String, message: String, now: Instant) =
        HandledClientFrame(
            ToolResultAck(
                chatId = chatId.toString(), toolCallId = toolCallId, threadId = threadId.toString(),
                status = "rejected", duplicate = false, error = ClientError(code, message), receivedAt = now.toString(),
            )
        )

    private fun rejectedCancel(
        chatId: UUID, requestId: String, threadId: UUID, code: String, message: String, now: Instant,
    ) = HandledClientFrame(
        ThreadCancelAck(
            chatId = chatId.toString(), requestId = requestId, threadId = threadId.toString(),
            status = "rejected", duplicate = false, error = ClientError(code, message), receivedAt = now.toString(),
        )
    )
}

internal class ClientContractException(val code: String, override val message: String) : RuntimeException(message)

private fun String.required(field: String): String = trim().takeIf { it.isNotEmpty() }
    ?: throw ClientContractException("invalid_request", "$field must not be empty.")

private fun String.uuid(field: String): UUID = try {
    UUID.fromString(this)
} catch (_: IllegalArgumentException) {
    throw ClientContractException("invalid_request", "$field must be a UUID.")
}

private fun String.toToolCallStatus(): ToolCallStatus = when (this) {
    "succeeded" -> ToolCallStatus.SUCCEEDED
    "failed" -> ToolCallStatus.FAILED
    "cancelled" -> ToolCallStatus.CANCELLED
    "timed_out" -> ToolCallStatus.TIMED_OUT
    else -> error("Unsupported tool status: $this")
}

private fun AgentExecutionStatus.isRunningThread(): Boolean =
    this == AgentExecutionStatus.QUEUED || this == AgentExecutionStatus.RUNNING
