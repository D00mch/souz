package ru.souz.backend.events.model

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

sealed interface AgentEventPayload

data class MessageCreatedPayload(
    val messageId: UUID,
    val seq: Long,
    val role: String,
    val content: String,
) : AgentEventPayload

data class MessageDeltaPayload(
    val messageId: UUID,
    val delta: String,
) : AgentEventPayload

data class MessageCompletedPayload(
    val messageId: UUID,
    val seq: Long,
    val role: String,
    val content: String,
) : AgentEventPayload

data class ExecutionStartedPayload(
    val executionId: UUID,
    val userMessageId: UUID? = null,
    val model: String? = null,
    val provider: String? = null,
    val streamingMessages: Boolean,
) : AgentEventPayload

data class ExecutionUsagePayload(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val precachedTokens: Int,
)

data class ExecutionFinishedPayload(
    val executionId: UUID,
    val assistantMessageId: UUID? = null,
    val status: String,
    val usage: ExecutionUsagePayload? = null,
) : AgentEventPayload

data class ExecutionFailedPayload(
    val executionId: UUID,
    val assistantMessageId: UUID? = null,
    val errorCode: String,
    val errorMessage: String,
) : AgentEventPayload

data class ExecutionCancelledPayload(
    val executionId: UUID,
    val assistantMessageId: UUID? = null,
) : AgentEventPayload

data class ToolCallStartedPayload(
    val toolCallId: String,
    val name: String,
    val argumentKeys: List<String>,
    val argumentsPreview: JsonNode? = null,
) : AgentEventPayload

data class ToolCallFinishedPayload(
    val toolCallId: String,
    val name: String,
    val resultPreview: JsonNode? = null,
    val durationMs: Long? = null,
) : AgentEventPayload

data class ToolCallFailedPayload(
    val toolCallId: String,
    val name: String,
    val error: String,
    val durationMs: Long? = null,
) : AgentEventPayload

data class ChoiceOptionItemPayload(
    val id: String,
    val label: String,
    val content: String? = null,
)

data class ChoiceRequestedPayload(
    val optionId: UUID,
    val kind: String,
    val title: String? = null,
    val selectionMode: String,
    val options: List<ChoiceOptionItemPayload>,
) : AgentEventPayload

data class ChoiceAnsweredPayload(
    val optionId: UUID,
    val status: String,
    val selectedOptionIds: List<String>,
    val freeText: String? = null,
    val metadata: Map<String, String> = emptyMap(),
) : AgentEventPayload

data class RawAgentEventPayload(
    val values: Map<String, String>,
) : AgentEventPayload

internal object AgentEventPayloadStorageCodec {
    private val mapper = jacksonObjectMapper()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    fun toStorageJson(payload: AgentEventPayload): JsonNode =
        when (payload) {
            is RawAgentEventPayload -> mapper.valueToTree(payload.values)
            else -> mapper.valueToTree(payload)
        }

    fun fromStorageJson(
        type: AgentEventType,
        payload: JsonNode?,
    ): AgentEventPayload {
        if (payload == null || payload.isNull) {
            return RawAgentEventPayload(emptyMap())
        }
        return decodeTyped(type, payload) ?: RawAgentEventPayload(payload.toLegacyStringMap())
    }

    private fun decodeTyped(
        type: AgentEventType,
        payload: JsonNode,
    ): AgentEventPayload? =
        runCatching {
            when (type) {
                AgentEventType.MESSAGE_CREATED -> mapper.treeToValue(payload, MessageCreatedPayload::class.java)
                AgentEventType.MESSAGE_DELTA -> mapper.treeToValue(payload, MessageDeltaPayload::class.java)
                AgentEventType.MESSAGE_COMPLETED -> mapper.treeToValue(payload, MessageCompletedPayload::class.java)
                AgentEventType.TOOL_CALL_STARTED -> mapper.treeToValue(payload, ToolCallStartedPayload::class.java)
                AgentEventType.TOOL_CALL_FINISHED -> mapper.treeToValue(payload, ToolCallFinishedPayload::class.java)
                AgentEventType.TOOL_CALL_FAILED -> mapper.treeToValue(payload, ToolCallFailedPayload::class.java)
                AgentEventType.OPTION_REQUESTED -> mapper.treeToValue(payload, ChoiceRequestedPayload::class.java)
                AgentEventType.OPTION_ANSWERED -> mapper.treeToValue(payload, ChoiceAnsweredPayload::class.java)
                AgentEventType.EXECUTION_STARTED -> mapper.treeToValue(payload, ExecutionStartedPayload::class.java)
                AgentEventType.EXECUTION_FINISHED -> mapper.treeToValue(payload, ExecutionFinishedPayload::class.java)
                AgentEventType.EXECUTION_FAILED -> mapper.treeToValue(payload, ExecutionFailedPayload::class.java)
                AgentEventType.EXECUTION_CANCELLED -> mapper.treeToValue(payload, ExecutionCancelledPayload::class.java)
            }
        }.getOrNull()

    private fun JsonNode.toLegacyStringMap(): Map<String, String> {
        if (!isObject) {
            return mapOf("value" to mapper.writeValueAsString(this))
        }
        val values = LinkedHashMap<String, String>()
        fieldNames().forEachRemaining { key ->
            val value = get(key)
            values[key] = when {
                value.isTextual -> value.asText()
                value.isNull -> ""
                else -> mapper.writeValueAsString(value)
            }
        }
        return values
    }
}
