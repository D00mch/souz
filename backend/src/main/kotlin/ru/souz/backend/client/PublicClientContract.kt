package ru.souz.backend.client

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode

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

data class RecognizedTextContent(
    val type: String,
    val source: String,
    val text: String,
)

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
data class AcceptedMessageAck(
    val kind: String = "ack",
    val chatId: String,
    val requestId: String,
    val status: String = "accepted",
    val duplicate: Boolean,
    val submission: SubmissionAck,
    val thread: ThreadAck,
    val error: Nothing? = null,
    val receivedAt: String,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class RejectedMessageAck(
    val kind: String = "ack",
    val chatId: String,
    val requestId: String,
    val status: String = "rejected",
    val duplicate: Boolean = false,
    val thread: Nothing? = null,
    val error: ClientError,
    val receivedAt: String,
)

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
)

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
)

internal val supportedClientTypes = setOf("backend", "mobile_app")
internal val supportedDeviceTypes = setOf("tv_box", "smart_speaker", "smartphone", "unknown")
internal val supportedDeviceCapabilities =
    setOf("speech", "screen", "device_tools", "user_permissions", "deep_links", "oauth")
internal val supportedToolResultStatuses = setOf("succeeded", "failed", "cancelled", "timed_out")
internal val supportedCancelReasons = setOf("user_requested", "superseded", "device_disconnected")
