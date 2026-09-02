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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.chat.repository.ChatRepository
import ru.souz.backend.common.BackendLlmSupport
import ru.souz.backend.client.model.ClientRequest
import ru.souz.backend.client.repository.ClientInputRepository
import ru.souz.backend.client.repository.ClientRequestRepository
import ru.souz.backend.client.repository.FollowUpInputResult
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.acceptsInput
import ru.souz.backend.execution.model.isActive
import ru.souz.backend.execution.repository.AgentExecutionRepository
import ru.souz.backend.execution.service.AgentExecutionService
import ru.souz.backend.http.BackendV1Exception
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
    private val clientInputRepository: ClientInputRepository,
    private val clientRequestRepository: ClientRequestRepository,
    private val toolCallRepository: ToolCallRepository,
    private val executionService: AgentExecutionService,
    private val registry: ClientThreadRuntimeRegistry,
    private val mapper: ObjectMapper = jacksonObjectMapper().registerKotlinModule(),
) {
    private data class ChatRequestLock(
        val mutex: Mutex = Mutex(),
        var users: Int = 0,
    )

    private val chatRequestLocksMutex = Mutex()
    private val chatRequestLocks = mutableMapOf<UUID, ChatRequestLock>()

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

    suspend fun handleMessage(chat: Chat, frame: MessageSubmitFrame): HandledClientFrame =
        withChatRequestLock(chat.id) { handleMessageLocked(chat, frame) }

    private suspend fun handleMessageLocked(chat: Chat, frame: MessageSubmitFrame): HandledClientFrame {
        val now = Instant.now()
        val requestId = frame.requestId.required("requestId")
        validateDevice(chat, frame.payload.device)
        validateContent(frame.payload.content)
        val requestedThreadId = frame.threadId?.uuid("threadId")
        val payloadHash = PublicPayloadHash.ofValue(
            linkedMapOf(
                "kind" to frame.kind,
                "threadId" to requestedThreadId?.toString(),
                "device" to frame.payload.device.copy(capabilities = frame.payload.device.capabilities.toSortedSet()),
                "content" to frame.payload.content,
                "meta" to frame.payload.meta,
            )
        )
        clientRequestRepository.get(chat.id, requestId)?.let { stored ->
            return handledStoredMessage(stored, frame, payloadHash, now, duplicate = true)
        }
        val selectedThreadId = requestedThreadId
            ?: executionRepository.findActive(chat.userId, chat.id)?.id
        return selectedThreadId?.let { continueThread(chat, frame, it, requestId, payloadHash, now) }
            ?: startThread(chat, frame, requestId, payloadHash, now)
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

    suspend fun handleCancel(chat: Chat, frame: ThreadCancelFrame): HandledClientFrame =
        withChatRequestLock(chat.id) { handleCancelLocked(chat, frame) }

    private suspend fun handleCancelLocked(chat: Chat, frame: ThreadCancelFrame): HandledClientFrame {
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
        if (!execution.status.acceptsInput()) {
            return rejectedCancel(chat.id, requestId, threadId, "thread_already_terminal", "Thread is already terminal.", now)
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
        val receipt = ClientRequest(chat.id, requestId, frame.kind, threadId, payloadHash, mapper.writeValueAsString(ack), now)
        val cancelled = try {
            registry.acceptCancellation(
                threadId = threadId,
                requestId = requestId,
                canAccept = {
                    executionRepository.getByChat(chat.userId, chat.id, threadId)?.status?.acceptsInput() == true
                },
                commit = {
                    executionService.cancelExecution(chat.userId, chat.id, threadId).also {
                        withContext(NonCancellable) {
                            clientRequestRepository.create(receipt)
                        }
                    }
                },
            )
        } catch (error: BackendV1Exception) {
            if (error.code == "invalid_request" && error.message == "Execution is not active.") {
                null
            } else {
                throw error
            }
        }
        if (cancelled == null) {
            val latest = executionRepository.getByChat(chat.userId, chat.id, threadId)
            return if (latest != null && !latest.status.acceptsInput()) {
                rejectedCancel(chat.id, requestId, threadId, "thread_already_terminal", "Thread is already terminal.", now)
            } else {
                rejectedCancel(chat.id, requestId, threadId, "message_rejected", "Live thread state is unavailable.", now)
            }
        }
        return HandledClientFrame(ack) { registry.ackSent(threadId, requestId) }
    }

    private suspend fun startThread(
        chat: Chat,
        frame: MessageSubmitFrame,
        requestId: String,
        payloadHash: String,
        now: Instant,
    ): HandledClientFrame {
        val deviceJson = mapper.writeValueAsString(frame.payload.device)
        val metadata = inputMetadata(frame, inputSeq = 1)
        val overrides = requestOverrides(frame.payload.meta)
        val threadId = UUID.randomUUID()
        val ack = acceptedMessage(chat.id, requestId, threadId, created = true, revision = 1, now = now)
        val receipt = ClientRequest(
            chat.id,
            requestId,
            frame.kind,
            threadId,
            payloadHash,
            mapper.writeValueAsString(ack),
            now,
        )
        registry.register(threadId, frame.payload.device)
        registry.registerAck(threadId, requestId)
        try {
            executionService.executeChatTurn(
                userId = chat.userId,
                chatId = chat.id,
                content = frame.payload.content.text,
                clientMessageId = requestId,
                initialClientRequest = receipt,
                requestOverrides = overrides,
                executionId = threadId,
                revision = 1,
                latestDeviceContextJson = deviceJson,
                userMessageMetadata = metadata,
                clientToolsEnabled = true,
            )
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                runCatching { reconcileFailedStart(chat, requestId, threadId) }
            }
            throw error
        } catch (error: Exception) {
            val stored = runCatching { reconcileFailedStart(chat, requestId, threadId) }
                .getOrElse { throw error }
            return stored?.let { request ->
                handledStoredMessage(request, frame, payloadHash, now, duplicate = request.threadId != threadId)
            } ?: rejectedMessage(
                chat.id, requestId, "message_rejected", error.message ?: "Message was rejected.", now
            )
        }
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
            ?: return rejectContinuation(chat.id, frame, requestId, null, payloadHash, now)
        if (!execution.status.acceptsInput()) {
            return rejectContinuation(chat.id, frame, requestId, execution, payloadHash, now)
        }
        val runtimeAvailable = withTimeoutOrNull(5_000.milliseconds) {
            registry.awaitRuntimeAvailable(threadId)
        } == true
        var activeExecution: AgentExecution? = null
        var commitRejection: FollowUpInputResult.Rejected? = null
        val ack = if (runtimeAvailable) {
            registry.acceptInput(
                threadId = threadId,
                requestId = requestId,
                device = frame.payload.device,
                input = frame.payload.content.text,
                canAccept = {
                    activeExecution = executionRepository.getByChat(chat.userId, chat.id, threadId)
                    activeExecution?.status?.acceptsInput() == true
                },
                commit = {
                    val current: AgentExecution = requireNotNull(activeExecution)
                    val nextInputSeq = current.revision + 1
                    val accepted: MessageSubmitAck = acceptedMessage(
                        chat.id, requestId, threadId, created = false,
                        revision = nextInputSeq,
                        now = now,
                    )
                    val receipt = ClientRequest(
                        chat.id, requestId, frame.kind, threadId,
                        payloadHash, mapper.writeValueAsString(accepted), now,
                    )
                    when (
                        val result = clientInputRepository.appendFollowUpInput(
                            execution = current,
                            request = receipt,
                            content = frame.payload.content.text,
                            metadata = inputMetadata(frame, nextInputSeq),
                            latestDeviceContextJson = mapper.writeValueAsString(frame.payload.device),
                        )
                    ) {
                        is FollowUpInputResult.Accepted -> accepted
                        is FollowUpInputResult.Rejected -> {
                            commitRejection = result
                            null
                        }
                    }
                },
            )
        } else {
            null
        }
        if (ack == null) {
            val rejection = commitRejection
            val latest = if (rejection == null) activeExecution ?: execution else rejection.currentExecution
            return rejectContinuation(
                chat.id, frame, requestId, latest, payloadHash, now,
            )
        }
        return HandledClientFrame(ack) { registry.ackSent(threadId, requestId) }
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

    private fun inputMetadata(frame: MessageSubmitFrame, inputSeq: Long): Map<String, String> = buildMap {
        put("inputSeq", inputSeq.toString())
        put("source", frame.payload.content.source)
        put("device", mapper.writeValueAsString(frame.payload.device))
        put("requestId", frame.requestId)
        frame.payload.meta?.let { put("requestMeta", mapper.writeValueAsString(it)) }
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

    private fun handledStoredMessage(
        stored: ClientRequest, frame: MessageSubmitFrame, payloadHash: String, now: Instant, duplicate: Boolean,
    ): HandledClientFrame {
        if (stored.kind != frame.kind || stored.payloadHash != payloadHash) {
            return rejectedMessage(
                stored.chatId, stored.requestId, "idempotency_conflict",
                "requestId was used with a different payload.", now,
            )
        }
        val original = mapper.readValue(stored.ackJson, MessageSubmitAck::class.java)
        return HandledClientFrame(original.copy(duplicate = duplicate)) {
            if (original.status == "accepted") stored.threadId?.let { registry.ackSent(it, stored.requestId) }
        }
    }

    private suspend fun reconcileFailedStart(
        chat: Chat, requestId: String, threadId: UUID,
    ): ClientRequest? = clientRequestRepository.get(chat.id, requestId).also { stored ->
        if (stored?.threadId == threadId) {
            executionService.failQueuedStartup(chat.userId, chat.id, threadId)
        } else {
            registry.ackSent(threadId, requestId)
            registry.discard(threadId)
        }
    }

    private suspend fun <T> withChatRequestLock(chatId: UUID, block: suspend () -> T): T {
        val lock = chatRequestLocksMutex.withLock {
            chatRequestLocks.getOrPut(chatId) { ChatRequestLock() }.also { it.users += 1 }
        }
        return try {
            lock.mutex.withLock { block() }
        } finally {
            withContext(NonCancellable) {
                chatRequestLocksMutex.withLock {
                    lock.users -= 1
                    if (lock.users == 0) chatRequestLocks.remove(chatId, lock)
                }
            }
        }
    }

    private fun rejectedMessage(chatId: UUID, requestId: String, code: String, message: String, now: Instant) =
        HandledClientFrame(
            MessageSubmitAck(
                chatId = chatId.toString(), requestId = requestId, status = "rejected", duplicate = false,
                error = ClientError(code, message), receivedAt = now.toString(),
            )
        )

    private suspend fun rejectContinuation(
        chatId: UUID,
        frame: MessageSubmitFrame,
        requestId: String,
        execution: AgentExecution?,
        payloadHash: String,
        now: Instant,
    ): HandledClientFrame {
        val (code, message) = when {
            execution == null -> "thread_not_found" to "Thread not found."
            !execution.status.isActive() -> "thread_already_terminal" to "Thread is already terminal."
            else -> "message_rejected" to "Thread no longer accepts input."
        }
        val handled = rejectedMessage(chatId, requestId, code, message, now)
        withContext(NonCancellable) {
            clientRequestRepository.create(
                ClientRequest(
                    chatId, requestId, frame.kind, execution?.id, payloadHash,
                    mapper.writeValueAsString(handled.response), now,
                )
            )
        }
        return handled
    }

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
