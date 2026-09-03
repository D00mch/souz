package ru.souz.backend.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.chat.model.ChatRole
import ru.souz.backend.chat.model.CLIENT_HISTORY_MESSAGE_METADATA_KEY
import ru.souz.backend.chat.repository.ChatRepository
import ru.souz.backend.common.BackendLlmSupport
import ru.souz.backend.client.model.ClientRequest
import ru.souz.backend.client.repository.ClientFollowUpInput
import ru.souz.backend.client.repository.ClientHistoryInput
import ru.souz.backend.client.repository.ClientRequestKey
import ru.souz.backend.client.repository.ClientRequestRepository
import ru.souz.backend.client.repository.ClientRequestResult
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.acceptsInput
import ru.souz.backend.execution.model.isActive
import ru.souz.backend.execution.repository.AgentExecutionRepository
import ru.souz.backend.execution.service.AgentExecutionService
import ru.souz.backend.settings.service.UserSettingsOverrides
import ru.souz.backend.toolcall.model.ToolCall
import ru.souz.backend.toolcall.model.ToolCallStatus
import ru.souz.backend.toolcall.repository.ToolCallContext
import ru.souz.backend.toolcall.repository.ToolCallRepository
import ru.souz.llms.ModelResolution
import ru.souz.llms.resolveChatModel
import ru.souz.llms.LlmProvider
import kotlin.time.Duration.Companion.milliseconds

internal data class HandledClientFrame(
    val response: Any,
    val afterSend: suspend () -> Unit = {},
)

internal class PublicClientService(
    private val chatRepository: ChatRepository,
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

    suspend fun threadStatus(chat: Chat, threadId: UUID, now: Instant = Instant.now()): PublicThreadStatusResponse {
        val execution = executionRepository.getByChat(chat.userId, chat.id, threadId)
            ?: throw ClientContractException("thread_not_found", "Thread not found.")
        return execution.toPublicThreadStatus(chat.id, now)
    }

    suspend fun handleMessage(chat: Chat, frame: MessageSubmitFrame): HandledClientFrame {
        val now = Instant.now()
        val requestId = frame.requestId.required("requestId")
        validateDevice(chat, frame.payload.device)
        validateContent(frame.payload.content)
        val requestedThreadId = frame.threadId?.uuid("threadId")
        val normalizedDevice = frame.payload.device.copy(capabilities = frame.payload.device.capabilities.toSortedSet())
        val key = ClientRequestKey(
            chatId = chat.id,
            requestId = requestId,
            kind = frame.kind,
            payloadHash = PublicPayloadHash.ofValue(
                linkedMapOf(
                    "kind" to frame.kind,
                    "threadId" to requestedThreadId?.toString(),
                    "device" to normalizedDevice,
                    "content" to frame.payload.content,
                    "meta" to frame.payload.meta,
                )
            ),
        )
        val result = clientRequestRepository.resolveMessage(
            userId = chat.userId,
            key = key,
            requestedThreadId = requestedThreadId,
            rejectedRequest = rejectedMessageRequest(key, now),
        )
        return when (result) {
            is ClientRequestResult.Continue -> continueThread(chat, frame, key, result.execution, now)
            ClientRequestResult.CreateThread -> startThread(chat, frame, key, now)
            else -> handledMessage(result, key, now)
        }
    }

    suspend fun handleHistory(chat: Chat, frame: HistoryAppendFrame): HandledClientFrame {
        val now = Instant.now()
        val requestId = frame.requestId.required("requestId")
        validateDevice(chat, frame.payload.device)
        validateContent(frame.payload.content)
        val role = frame.payload.role.takeIf { it in supportedMessageRoles }
            ?: throw ClientContractException("invalid_request", "Unsupported message role.")
        val key = ClientRequestKey(
            chatId = chat.id,
            requestId = requestId,
            kind = frame.kind,
            payloadHash = PublicPayloadHash.ofValue(
                linkedMapOf(
                    "kind" to frame.kind,
                    "role" to role,
                    "device" to frame.payload.device.copy(
                        capabilities = frame.payload.device.capabilities.toSortedSet()
                    ),
                    "content" to frame.payload.content,
                    "meta" to frame.payload.meta,
                )
            ),
        )
        val ack = HistoryAppendAck(
            chatId = chat.id.toString(),
            requestId = requestId,
            status = "accepted",
            duplicate = false,
            receivedAt = now.toString(),
        )
        val result = clientRequestRepository.commitHistory(
            userId = chat.userId,
            key = key,
            input = ClientHistoryInput(
                role = role.toChatRole(),
                content = frame.payload.content.text,
                metadata = inputMetadata(
                    requestId = requestId,
                    device = frame.payload.device,
                    content = frame.payload.content,
                    meta = frame.payload.meta,
                ) + (CLIENT_HISTORY_MESSAGE_METADATA_KEY to "true"),
            ),
            acceptedRequest = key.request(threadId = null, response = ack, now = now),
        )
        if (result is ClientRequestResult.HistoryAccepted) {
            withContext(NonCancellable) {
                runCatching { registry.notifyHistoryPending(chat.id, result.message.seq) }
                    .onFailure { failure ->
                        logger.warn(
                            "Failed to notify local runtime about history for chat {} through message {}.",
                            chat.id,
                            result.message.seq,
                            failure,
                        )
                    }
            }
        }
        return handledHistory(result, key, now)
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
        if (frame.error?.details?.isObject == false) {
            return rejectedTool(chat.id, threadId, toolCallId, "invalid_request", "error.details must be an object.", now)
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
            return terminalToolResult(chat.id, threadId, toolCallId, existing, payloadHash, now)
        }
        if (status != "timed_out" && existing.deadlineAt?.let { !now.isBefore(it) } == true) {
            return timeOutExpiredTool(chat.id, threadId, toolCallId, context, now)
        }
        val execution = executionRepository.getByChat(chat.userId, chat.id, threadId)
        if (execution == null || !execution.status.acceptsInput()) {
            return rejectedTool(chat.id, threadId, toolCallId, "thread_already_terminal", "Thread is not running.", now)
        }
        if (!registry.contains(threadId)) {
            return rejectedTool(chat.id, threadId, toolCallId, "message_rejected", "Thread runtime is unavailable on this Souz instance.", now)
        }
        val completed = toolCallRepository.completeClientCall(
            context = context,
            status = status.toToolCallStatus(),
            resultJson = frame.result?.let(mapper::writeValueAsString),
            errorJson = frame.error?.let(mapper::writeValueAsString),
            payloadHash = payloadHash,
            receivedAt = now,
        )
        if (completed == null) {
            val latest = toolCallRepository.get(context)
            return if (latest != null && latest.status != ToolCallStatus.RUNNING) {
                terminalToolResult(chat.id, threadId, toolCallId, latest, payloadHash, now)
            } else {
                rejectedTool(chat.id, threadId, toolCallId, "message_rejected", "Tool call is no longer pending.", now)
            }
        }
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
        val key = ClientRequestKey(
            chatId = chat.id,
            requestId = requestId,
            kind = frame.kind,
            payloadHash = PublicPayloadHash.ofValue(
                linkedMapOf("kind" to frame.kind, "threadId" to threadId.toString(), "reason" to frame.reason)
            ),
        )
        val ack = ThreadCancelAck(
            chatId = chat.id.toString(),
            requestId = requestId,
            threadId = threadId.toString(),
            status = "accepted",
            duplicate = false,
            error = null,
            receivedAt = now.toString(),
        )
        withTimeoutOrNull(5_000.milliseconds) {
            registry.awaitRuntimeAvailable(threadId)
        }
        val result = registry.commitCancellation(
            threadId = threadId,
            requestId = requestId,
            commit = { runtimeAvailable ->
                clientRequestRepository.cancel(
                    userId = chat.userId,
                    key = key,
                    threadId = threadId,
                    runtimeAvailable = runtimeAvailable,
                    acceptedRequest = key.request(threadId, ack, now),
                    rejectedRequest = rejectedCancelRequest(key, threadId, now),
                )
            },
            afterAccepted = { accepted ->
                executionService.propagateCancellation(accepted.execution)
            },
        )
        return handledCancel(result, key, threadId, now)
    }

    private suspend fun startThread(
        chat: Chat,
        frame: MessageSubmitFrame,
        key: ClientRequestKey,
        now: Instant,
    ): HandledClientFrame {
        val deviceJson = mapper.writeValueAsString(frame.payload.device)
        val metadata = inputMetadata(frame, inputSeq = 1)
        val prepared = executionService.prepareChatTurn(
            userId = chat.userId,
            chatId = chat.id,
            content = frame.payload.content.text,
            clientMessageId = key.requestId,
            requestOverrides = requestOverrides(frame.payload.meta),
            revision = 1,
            latestDeviceContextJson = deviceJson,
            userMessageMetadata = metadata,
            clientToolsEnabled = true,
        )
        val threadId = prepared.execution.id
        val ack = acceptedMessage(chat.id, key.requestId, threadId, created = true, revision = 1, now = now)
        val result = withContext(NonCancellable) {
            registry.register(chat.id, threadId, frame.payload.device, key.requestId)
            var resolution: ClientRequestResult? = null
            try {
                clientRequestRepository.resolveMessage(
                    userId = chat.userId,
                    key = key,
                    requestedThreadId = null,
                    newExecution = prepared.execution,
                    acceptedRequest = key.request(threadId, ack, now),
                    rejectedRequest = rejectedMessageRequest(key, now),
                ).also { resolution = it }
            } finally {
                if (resolution !is ClientRequestResult.Accepted) registry.discard(threadId)
            }
        }
        if (result !is ClientRequestResult.Accepted) {
            return if (result is ClientRequestResult.Continue) {
                continueThread(chat, frame, key, result.execution, now)
            } else {
                handledMessage(result, key, now)
            }
        }
        val startupFailure = runCatching {
            withContext(NonCancellable) { executionService.startPreparedChatTurn(prepared) }
        }.exceptionOrNull()
        startupFailure?.let { failure ->
            withContext(NonCancellable) {
                executionService.failStartup(prepared.execution)
            }
            if (failure is CancellationException) throw failure
        }
        return handledMessage(result, key, now)
    }

    private suspend fun continueThread(
        chat: Chat,
        frame: MessageSubmitFrame,
        key: ClientRequestKey,
        execution: AgentExecution,
        now: Instant,
    ): HandledClientFrame {
        val threadId = execution.id
        val input = ClientFollowUpInput(
            content = frame.payload.content.text,
            metadata = inputMetadata(frame),
            latestDeviceContextJson = mapper.writeValueAsString(frame.payload.device),
        )
        suspend fun commit(afterSeq: Long, input: ClientFollowUpInput?) = clientRequestRepository.commitFollowUp(
            userId = chat.userId,
            key = key,
            threadId = threadId,
            afterSeq = afterSeq,
            input = input,
            acceptedRequest = { revision ->
                key.request(
                    threadId,
                    acceptedMessage(
                        chat.id,
                        key.requestId,
                        threadId,
                        created = false,
                        revision = revision,
                        now = now,
                    ),
                    now,
                )
            },
            rejectedRequest = rejectedMessageRequest(key, now),
        )
        val runtimeAvailable = withTimeoutOrNull(5_000.milliseconds) {
            registry.awaitRuntimeAvailable(threadId)
        } == true
        val result = if (runtimeAvailable) {
            registry.acceptInput(
                threadId = threadId,
                requestId = key.requestId,
                device = frame.payload.device,
                commit = { afterSeq -> commit(afterSeq, input) },
            )
        } else {
            null
        } ?: commit(afterSeq = 0L, input = null)
        return handledMessage(result, key, now)
    }

    private fun acceptedMessage(
        chatId: UUID,
        requestId: String,
        threadId: UUID,
        created: Boolean,
        revision: Long,
        now: Instant,
    ) = MessageSubmitAck(
        chatId = chatId.toString(),
        requestId = requestId,
        status = "accepted",
        duplicate = false,
        submission = SubmissionAck(revision),
        thread = ThreadAck(threadId.toString(), created = created, revision = revision),
        receivedAt = now.toString(),
    )

    private fun inputMetadata(frame: MessageSubmitFrame, inputSeq: Long? = null): Map<String, String> = inputMetadata(
        requestId = frame.requestId,
        device = frame.payload.device,
        content = frame.payload.content,
        meta = frame.payload.meta,
        inputSeq = inputSeq,
    )

    private fun inputMetadata(
        requestId: String,
        device: ClientDevice,
        content: RecognizedTextContent,
        meta: ClientRequestMeta?,
        inputSeq: Long? = null,
    ): Map<String, String> = buildMap {
        inputSeq?.let { put("inputSeq", it.toString()) }
        put("source", content.source)
        put("device", mapper.writeValueAsString(device))
        put("requestId", requestId)
        meta?.let { put("requestMeta", mapper.writeValueAsString(it)) }
    }

    private fun requestOverrides(meta: ClientRequestMeta?): UserSettingsOverrides = UserSettingsOverrides(
        defaultModel = meta?.model?.let { raw ->
            when (
                val resolution = resolveChatModel(
                    rawModel = raw,
                    supportedProviders = BackendLlmSupport.chatProviders,
                )
            ) {
                is ModelResolution.Resolved -> resolution.value
                is ModelResolution.Unknown -> throw ClientContractException(
                    "invalid_request",
                    "payload.meta.model must be a known model alias.",
                )
                is ModelResolution.Ambiguous -> throw ClientContractException(
                    "invalid_request",
                    "payload.meta.model is ambiguous; use a model enum name.",
                )
                is ModelResolution.UnsupportedProvider -> {
                    val message = if (resolution.provider == LlmProvider.GIGA) {
                        BackendLlmSupport.GIGA_UNSUPPORTED_MESSAGE
                    } else {
                        "payload.meta.model uses an unsupported provider."
                    }
                    throw ClientContractException("invalid_request", message)
                }
            }
        },
        locale = meta?.locale?.let { Locale.forLanguageTag(it).takeIf { locale -> locale.language.isNotBlank() } },
        timeZone = meta?.timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() },
        streamingMessages = true,
    )

    private suspend fun timeOutExpiredTool(
        chatId: UUID,
        threadId: UUID,
        toolCallId: String,
        context: ToolCallContext,
        now: Instant,
    ): HandledClientFrame {
        val error = ClientError("client_tool_timed_out", "Client tool result deadline expired.")
        val outcome = ClientToolOutcome("timed_out", null, error)
        val completed = toolCallRepository.completeClientCall(
            context = context,
            status = ToolCallStatus.TIMED_OUT,
            resultJson = null,
            errorJson = mapper.writeValueAsString(error),
            payloadHash = PublicPayloadHash.ofValue(mapOf("status" to "timed_out", "error" to error)),
            receivedAt = now,
        )
        return HandledClientFrame(
            response = ToolResultAck(
                chatId = chatId.toString(),
                toolCallId = toolCallId,
                threadId = threadId.toString(),
                status = "rejected",
                duplicate = false,
                error = error,
                receivedAt = now.toString(),
            ),
            afterSend = {
                if (completed != null) registry.finishTool(threadId, toolCallId, outcome)
            },
        )
    }

    private fun ToolCall.toClientToolOutcome(): ClientToolOutcome =
        ClientToolOutcome(
            status = status.value,
            result = resultJson?.let { mapper.readTree(it) },
            error = errorJson?.let { stored ->
                runCatching { mapper.readValue(stored, ClientError::class.java) }
                    .getOrElse { ClientError("client_tool_failed", "Client tool failed.") }
            },
        )

    private fun AgentExecution.toPublicThreadStatus(chatId: UUID, now: Instant): PublicThreadStatusResponse {
        val active = status.isActive()
        val leaseAlive = runtimeLeaseUntil?.isAfter(now) ?: true
        val alive = active && leaseAlive
        return PublicThreadStatusResponse(
            chatId = chatId.toString(),
            threadId = id.toString(),
            status = status.value,
            alive = alive,
            acceptsInput = status.acceptsInput() && alive,
            revision = revision,
            startedAt = startedAt.toString(),
            finishedAt = finishedAt?.toString(),
            runtimeLeaseExpiresAt = runtimeLeaseUntil?.toString(),
            error = errorCode?.let { code ->
                ClientError(code.toPublicClientErrorCode(), errorMessage ?: "Thread failed.")
            },
            observedAt = now.toString(),
        )
    }

    private fun terminalToolResult(
        chatId: UUID,
        threadId: UUID,
        toolCallId: String,
        toolCall: ToolCall,
        payloadHash: String,
        now: Instant,
    ): HandledClientFrame = if (toolCall.resultPayloadHash == payloadHash) {
        val outcome = toolCall.toClientToolOutcome()
        HandledClientFrame(acceptedTool(chatId, threadId, toolCallId, duplicate = true, now)) {
            registry.finishTool(threadId, toolCallId, outcome)
        }
    } else {
        rejectedTool(
            chatId,
            threadId,
            toolCallId,
            "idempotency_conflict",
            "toolCallId already has a different terminal result.",
            now,
        )
    }

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

    private fun handledMessage(
        result: ClientRequestResult,
        key: ClientRequestKey,
        now: Instant,
    ): HandledClientFrame {
        if (result is ClientRequestResult.Conflict) {
            return rejectedMessage(
                key.chatId, key.requestId, "idempotency_conflict",
                "requestId was used with a different payload.", now,
            )
        }
        val request = result.storedRequest()
        val original = mapper.readValue(request.ackJson, MessageSubmitAck::class.java)
        return HandledClientFrame(original.copy(duplicate = result is ClientRequestResult.Duplicate)) {
            if (original.status == "accepted") request.threadId?.let { registry.ackSent(it, request.requestId) }
        }
    }

    private fun handledHistory(
        result: ClientRequestResult,
        key: ClientRequestKey,
        now: Instant,
    ): HandledClientFrame {
        if (result is ClientRequestResult.Conflict) {
            return rejectedHistory(
                key.chatId,
                key.requestId,
                "idempotency_conflict",
                "requestId was used with a different payload.",
                now,
            )
        }
        val original = mapper.readValue(result.storedRequest().ackJson, HistoryAppendAck::class.java)
        return HandledClientFrame(original.copy(duplicate = result is ClientRequestResult.Duplicate))
    }

    private fun handledCancel(
        result: ClientRequestResult,
        key: ClientRequestKey,
        threadId: UUID,
        now: Instant,
    ): HandledClientFrame {
        if (result is ClientRequestResult.Conflict) {
            return rejectedCancel(
                key.chatId, key.requestId, threadId, "idempotency_conflict",
                "requestId was used with a different payload.", now,
            )
        }
        val request = result.storedRequest()
        val original = mapper.readValue(request.ackJson, ThreadCancelAck::class.java)
        return HandledClientFrame(original.copy(duplicate = result is ClientRequestResult.Duplicate)) {
            if (original.status == "accepted") registry.ackSent(threadId, request.requestId)
        }
    }

    private fun rejectedMessageRequest(
        key: ClientRequestKey,
        now: Instant,
    ): (AgentExecution?) -> ClientRequest = { execution ->
        val (code, message) = when {
            execution == null -> "thread_not_found" to "Thread not found."
            !execution.status.isActive() -> "thread_already_terminal" to "Thread is already terminal."
            else -> "message_rejected" to "Thread no longer accepts input."
        }
        key.request(
            execution?.id,
            MessageSubmitAck(
                chatId = key.chatId.toString(), requestId = key.requestId,
                status = "rejected", duplicate = false, error = ClientError(code, message),
                receivedAt = now.toString(),
            ),
            now,
        )
    }

    private fun rejectedCancelRequest(
        key: ClientRequestKey,
        threadId: UUID,
        now: Instant,
    ): (AgentExecution?) -> ClientRequest = { execution ->
        val (code, message) = when {
            execution == null -> "thread_not_found" to "Thread not found."
            !execution.status.acceptsInput() -> "thread_already_terminal" to "Thread is already terminal."
            else -> "message_rejected" to "Live thread state is unavailable."
        }
        key.request(
            execution?.id,
            ThreadCancelAck(
                chatId = key.chatId.toString(), requestId = key.requestId, threadId = threadId.toString(),
                status = "rejected", duplicate = false, error = ClientError(code, message),
                receivedAt = now.toString(),
            ),
            now,
        )
    }

    private fun ClientRequestKey.request(threadId: UUID?, response: Any, now: Instant) = ClientRequest(
        chatId = chatId,
        requestId = requestId,
        kind = kind,
        threadId = threadId,
        payloadHash = payloadHash,
        ackJson = mapper.writeValueAsString(response),
        receivedAt = now,
    )

    private fun rejectedMessage(
        chatId: UUID,
        requestId: String,
        code: String,
        message: String,
        now: Instant,
    ) =
        HandledClientFrame(
            MessageSubmitAck(
                chatId = chatId.toString(), requestId = requestId, status = "rejected", duplicate = false,
                error = ClientError(code, message), receivedAt = now.toString(),
            )
        )

    private fun rejectedHistory(
        chatId: UUID,
        requestId: String,
        code: String,
        message: String,
        now: Instant,
    ) = HandledClientFrame(
        HistoryAppendAck(
            chatId = chatId.toString(), requestId = requestId, status = "rejected", duplicate = false,
            error = ClientError(code, message), receivedAt = now.toString(),
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

    private companion object {
        val logger = LoggerFactory.getLogger(PublicClientService::class.java)
    }
}

private fun ClientRequestResult.storedRequest(): ClientRequest = when (this) {
    is ClientRequestResult.Accepted -> request
    is ClientRequestResult.HistoryAccepted -> request
    is ClientRequestResult.Duplicate -> request
    is ClientRequestResult.Rejected -> request
    else -> error("Request has no stored transport result: $this")
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

private fun String.toChatRole(): ChatRole = when (this) {
    MESSAGE_ROLE_USER -> ChatRole.USER
    MESSAGE_ROLE_ASSISTANT -> ChatRole.ASSISTANT
    else -> error("Unsupported message role: $this")
}

private fun String.toPublicClientErrorCode(): String =
    if (this in publicClientErrorCodes) this else "internal_error"

private val publicClientErrorCodes = setOf(
    "invalid_request",
    "chat_not_found",
    "thread_not_found",
    "thread_already_terminal",
    "tool_call_not_found",
    "idempotency_conflict",
    "feature_disabled",
    "message_rejected",
    "client_tool_timed_out",
    "internal_error",
)
