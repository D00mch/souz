package ru.souz.backend.client

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonUnwrapped
import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant

data class CreateClientChatRequest(
    val userId: String,
    val requestId: String,
    val clientType: String,
    val title: String? = null,
)

data class CreateClientChatResponse(
    val requestId: String,
    val duplicate: Boolean,
    val chat: ClientChatDto,
)

data class ClientChatDto(
    val id: String,
    val title: String?,
)

data class ClientDevice(
    val userId: String,
    val deviceId: String,
    val deviceType: String,
    val capabilities: Set<String>,
    val appVersion: String? = null,
    val platform: String? = null,
)

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "type",
    visible = true,
)
@JsonSubTypes(
    JsonSubTypes.Type(value = RecognizedTextContent::class, name = "text"),
    JsonSubTypes.Type(value = HistoryToolExchangeContent::class, name = "tool_exchange"),
)
sealed interface HistoryAppendContent {
    val type: String
}

data class RecognizedTextContent(
    override val type: String,
    val source: String,
    val text: String,
) : HistoryAppendContent

data class HistoryToolExchangeContent(
    override val type: String,
    val name: String,
    val arguments: Map<String, JsonNode>,
    val output: Map<String, JsonNode>,
) : HistoryAppendContent

data class ClientRequestMeta(
    val locale: String? = null,
    val timeZone: String? = null,
    val model: String? = null,
)

data class MessageSubmitPayload(
    val device: ClientDevice,
    val content: RecognizedTextContent,
    val meta: ClientRequestMeta? = null,
)

data class MessageSubmitFrame(
    val kind: String,
    val chatId: String,
    val requestId: String,
    val threadId: String? = null,
    val payload: MessageSubmitPayload,
)

data class HistoryAppendPayload(
    val role: String,
    val content: HistoryAppendContent,
)

data class HistoryAppendFrame(
    val kind: String,
    val chatId: String,
    val requestId: String,
    val payload: HistoryAppendPayload,
)

data class ToolResultFrame(
    val kind: String,
    val chatId: String,
    val threadId: String,
    val toolCallId: String,
    val status: String,
    val result: JsonNode? = null,
    val error: ClientError? = null,
)

data class ThreadCancelFrame(
    val kind: String,
    val chatId: String,
    val requestId: String,
    val threadId: String,
    val reason: String? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ClientError(
    val code: String,
    val message: String,
    val details: JsonNode? = null,
)

data class SubmissionAck(
    val inputSeq: Long,
)

data class ThreadAck(
    val id: String,
    val created: Boolean,
    val status: String = "running",
    val revision: Long,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class PublicThreadStatusResponse(
    val chatId: String,
    val threadId: String,
    val status: String,
    val alive: Boolean,
    val acceptsInput: Boolean,
    val revision: Long,
    val startedAt: String,
    val finishedAt: String? = null,
    val runtimeLeaseExpiresAt: String? = null,
    val error: ClientError? = null,
    val observedAt: String,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class ThreadStatusFrame(
    @field:JsonUnwrapped
    val threadStatus: PublicThreadStatusResponse,
    val kind: String = "status",
    val type: String = "thread.status",
    val requestId: String? = null,
)

internal fun PublicThreadStatusResponse.toStatusFrame(requestId: String? = null): ThreadStatusFrame =
    ThreadStatusFrame(threadStatus = this, requestId = requestId)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class MessageSubmitAck(
    val kind: String = "ack",
    val chatId: String,
    val requestId: String,
    val status: String,
    val duplicate: Boolean,
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val submission: SubmissionAck? = null,
    val thread: ThreadAck? = null,
    val error: ClientError? = null,
    val receivedAt: String,
) {
    companion object {
        internal fun rejected(chatId: String, requestId: String, error: ClientError, now: Instant) =
            MessageSubmitAck(
                chatId = chatId, requestId = requestId, status = "rejected", duplicate = false,
                error = error, receivedAt = now.toString(),
            )
    }
}

@JsonInclude(JsonInclude.Include.ALWAYS)
data class HistoryAppendAck(
    val kind: String = "ack",
    val chatId: String,
    val requestId: String,
    val status: String,
    val duplicate: Boolean,
    val error: ClientError? = null,
    val receivedAt: String,
) {
    companion object {
        internal fun rejected(chatId: String, requestId: String, error: ClientError, now: Instant) =
            HistoryAppendAck(
                chatId = chatId, requestId = requestId, status = "rejected", duplicate = false,
                error = error, receivedAt = now.toString(),
            )
    }
}

@JsonInclude(JsonInclude.Include.ALWAYS)
data class ToolResultAck(
    val kind: String = "ack",
    val chatId: String,
    val toolCallId: String,
    val threadId: String,
    val status: String,
    val duplicate: Boolean,
    val error: ClientError?,
    val receivedAt: String,
) {
    companion object {
        internal fun rejected(chatId: String, threadId: String, toolCallId: String, error: ClientError, now: Instant) =
            ToolResultAck(
                chatId = chatId, threadId = threadId, toolCallId = toolCallId, status = "rejected", duplicate = false,
                error = error, receivedAt = now.toString(),
            )
    }
}

@JsonInclude(JsonInclude.Include.ALWAYS)
data class ThreadCancelAck(
    val kind: String = "ack",
    val chatId: String,
    val requestId: String,
    val threadId: String,
    val status: String,
    val duplicate: Boolean,
    val error: ClientError?,
    val receivedAt: String,
) {
    companion object {
        internal fun rejected(chatId: String, requestId: String, threadId: String, error: ClientError, now: Instant) =
            ThreadCancelAck(
                chatId = chatId, requestId = requestId, threadId = threadId, status = "rejected", duplicate = false,
                error = error, receivedAt = now.toString(),
            )
    }
}

internal val supportedClientTypes = setOf("backend", "mobile_app")
internal const val MESSAGE_ROLE_USER = "user"
internal const val MESSAGE_ROLE_ASSISTANT = "assistant"
internal val supportedMessageRoles = setOf(MESSAGE_ROLE_USER, MESSAGE_ROLE_ASSISTANT)
internal val supportedDeviceTypes = setOf("tv_box", "smart_speaker", "smartphone", "unknown")
internal val supportedDeviceCapabilities =
    setOf("speech", "screen", "device_tools", "user_permissions", "deep_links", "oauth")
internal val supportedToolResultStatuses = setOf("succeeded", "failed", "cancelled", "timed_out")
internal val supportedCancelReasons = setOf("user_requested", "superseded", "device_disconnected")
