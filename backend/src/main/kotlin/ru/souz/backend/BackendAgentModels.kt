package ru.souz.backend

import java.time.DateTimeException
import java.time.ZoneId
import java.util.UUID

data class AgentRequest(
    val requestId: String = "",
    val userId: String = "",
    val conversationId: String = "",
    val prompt: String = "",
    val model: String = "",
    val contextSize: Int = 0,
    val source: String = "",
    val locale: String = "",
    val timeZone: String = "",
)

data class AgentUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val precachedTokens: Int,
)

data class AgentResponse(
    val requestId: String,
    val conversationId: String,
    val userMessageId: String,
    val assistantMessageId: String,
    val content: String,
    val model: String,
    val provider: String,
    val contextSize: Int,
    val usage: AgentUsage,
)

data class AgentConversationKey(
    val userId: String,
    val conversationId: String,
)

internal data class ValidatedAgentRequest(
    val requestId: String,
    val userId: String,
    val conversationId: String,
    val prompt: String,
    val model: String,
    val contextSize: Int,
    val locale: String,
    val timeZone: String,
)

internal fun AgentRequest.validated(): ValidatedAgentRequest {
    source.trim().takeIf { it.isNotEmpty() }
        ?: throw BackendRequestException(400, "source must not be empty.")
    val localeValue = locale.trim().takeIf { it.isNotEmpty() }
        ?: throw BackendRequestException(400, "locale must not be empty.")
    val timeZoneValue = timeZone.trim()
    validateTimeZone(timeZoneValue)

    return ValidatedAgentRequest(
        requestId = requestId.requireUuid("requestId"),
        userId = userId.requireUuid("userId"),
        conversationId = conversationId.requireUuid("conversationId"),
        prompt = prompt.trim().takeIf { it.isNotEmpty() }
            ?: throw BackendRequestException(400, "prompt must not be empty."),
        model = model.trim().takeIf { it.isNotEmpty() }
            ?: throw BackendRequestException(400, "model must not be empty."),
        contextSize = contextSize.takeIf { it > 0 }
            ?: throw BackendRequestException(400, "contextSize must be positive."),
        locale = localeValue,
        timeZone = timeZoneValue,
    )
}

private fun String.requireUuid(fieldName: String): String =
    trim().let { value ->
        runCatching { UUID.fromString(value).toString() }
            .getOrElse { throw BackendRequestException(400, "$fieldName must be a UUID.") }
    }

private fun validateTimeZone(value: String) {
    if (value.isEmpty()) {
        throw BackendRequestException(400, "timeZone must not be empty.")
    }
    try {
        ZoneId.of(value)
    } catch (e: DateTimeException) {
        throw BackendRequestException(400, "timeZone must be a valid time zone.")
    }
}
