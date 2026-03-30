package ru.souz.service.telemetry

import ru.souz.llms.GigaResponse

data class TelemetryEvent(
    val eventId: String,
    val type: String,
    val occurredAtMs: Long,
    val userId: String,
    val deviceId: String,
    val appSessionId: String,
    val conversationId: String? = null,
    val requestId: String? = null,
    val payload: Map<String, Any?> = emptyMap(),
)

data class TelemetryClientMetadata(
    val appName: String,
    val appVersion: String,
    val edition: String,
    val userId: String,
    val deviceId: String,
    val installationId: String? = null,
    val appSessionId: String,
    val osName: String,
    val osVersion: String,
    val osArch: String,
    val sentAtMs: Long,
)

data class TelemetryBatchRequest(
    val schemaVersion: Int = 1,
    val client: TelemetryClientMetadata,
    val events: List<TelemetryEvent>,
)

data class TelemetryInstallationRegistrationRequest(
    val schemaVersion: Int = 1,
    val userId: String,
    val deviceId: String,
    val publicKey: String,
    val keyAlgorithm: String,
    val client: TelemetryClientMetadata,
)

data class TelemetryInstallationRegistrationResponse(
    val installationId: String,
)

data class QueuedTelemetryEvent(
    val rowId: Long,
    val event: TelemetryEvent,
    val attemptCount: Int,
)

data class TelemetryRequestContext(
    val requestId: String,
    val conversationId: String,
    val source: TelemetryRequestSource,
    val model: String,
    val provider: String,
    val inputLengthChars: Int,
    val attachedFilesCount: Int,
    val startedAtMs: Long,
)

data class TelemetryDiagnostics(
    val captureEnabled: Boolean,
    val sendConfigured: Boolean,
    val queuedEvents: Int,
    val lastSuccessfulFlushAtMs: Long?,
    val lastErrorMessage: String?,
    val userId: String,
    val deviceId: String,
    val installationId: String?,
    val appSessionId: String,
)

data class TelemetryFlushResult(
    val success: Boolean,
    val acceptedEvents: Int,
    val queuedEventsAfter: Int,
    val message: String,
)

enum class TelemetryEventType(val wireName: String) {
    APP_OPENED("app_opened"),
    APP_CLOSED("app_closed"),
    CONVERSATION_STARTED("conversation_started"),
    CONVERSATION_FINISHED("conversation_finished"),
    REQUEST_FINISHED("request_finished"),
    TOOL_EXECUTED("tool_executed"),
}

enum class TelemetryRequestSource(val wireName: String) {
    CHAT_UI("chat_ui"),
    VOICE_INPUT("voice_input"),
    TELEGRAM_BOT("telegram_bot"),
}

enum class TelemetryRequestStatus(val wireName: String) {
    SUCCESS("success"),
    ERROR("error"),
    CANCELLED("cancelled"),
}

enum class TelemetryConversationStartReason(val wireName: String) {
    CHAT_UI("chat_ui"),
    VOICE_INPUT("voice_input"),
    TELEGRAM_BOT("telegram_bot"),
}

enum class TelemetryConversationEndReason(val wireName: String) {
    NEW_CONVERSATION("new_conversation"),
    CLEAR_CONTEXT("clear_context"),
    VIEW_MODEL_CLEARED("view_model_cleared"),
}

internal data class ActiveTelemetryRequest(
    val context: TelemetryRequestContext,
    val toolCallCount: Int = 0,
)

internal data class ConversationMetrics(
    val startedAtMs: Long,
    val startReason: TelemetryConversationStartReason,
    val requestCount: Int = 0,
    val toolCallCount: Int = 0,
    val tokenUsage: GigaResponse.Usage = ZERO_USAGE,
)

internal data class TelemetryInstallationIdentity(
    val userId: String,
    val deviceId: String,
    val keyPair: TelemetrySigningKeyPair,
)

internal const val TELEMETRY_SCHEMA_VERSION = 1
internal val ZERO_USAGE = GigaResponse.Usage(0, 0, 0, 0)
