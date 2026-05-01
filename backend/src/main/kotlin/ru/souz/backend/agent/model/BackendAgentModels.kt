package ru.souz.backend.agent.model

import java.time.DateTimeException
import java.time.ZoneId
import java.util.UUID
import ru.souz.backend.common.BackendRequestException

/** HTTP request body for one backend `/agent` turn. */
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

/** Token usage block returned by backend `/agent`. */
data class AgentUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val precachedTokens: Int,
)

/** HTTP success response for one backend `/agent` turn. */
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

/** Stable backend conversation identifier composed from user and conversation ids. */
data class AgentConversationKey(
    val userId: String,
    val conversationId: String,
)

/** Internal request model shared by legacy `/agent` and stage-3 chat-oriented turns. */
internal data class BackendConversationTurnRequest(
    val prompt: String,
    val model: String,
    val contextSize: Int,
    val locale: String,
    val timeZone: String,
    val temperature: Float? = null,
    val systemPrompt: String? = null,
    val streamingMessages: Boolean? = null,
)

/** Fully validated backend request passed into runtime orchestration. */
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

internal fun ValidatedAgentRequest.toConversationTurnRequest(): BackendConversationTurnRequest =
    BackendConversationTurnRequest(
        prompt = prompt,
        model = model,
        contextSize = contextSize,
        locale = locale,
        timeZone = timeZone,
    )

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
