package ru.souz.backend.http

import io.ktor.openapi.AdditionalProperties
import io.ktor.openapi.GenericElement
import io.ktor.openapi.JsonSchema
import io.ktor.openapi.JsonSchemaDiscriminator
import io.ktor.openapi.JsonType
import io.ktor.openapi.ReferenceOr
import ru.souz.backend.events.model.AgentEventType

/** Documentation-only schemas for the canonical backend event transport contract. */
internal object BackendEventOpenApiSchemas {
    const val DURABLE_EVENT = "BackendDurableEventEnvelope"
    const val LEGACY_DURABLE_EVENT = "BackendLegacyDurableEventEnvelope"
    const val REPLAY_EVENT = "BackendReplayEventEnvelope"
    const val MESSAGE_DELTA_EVENT = "BackendMessageDeltaEventEnvelope"
    const val REPLAY_RESPONSE = "BackendDurableEventReplayResponse"

    private const val MESSAGE_CREATED_PAYLOAD = "BackendMessageCreatedEventPayload"
    private const val MESSAGE_COMPLETED_PAYLOAD = "BackendMessageCompletedEventPayload"
    private const val MESSAGE_DELTA_PAYLOAD = "BackendMessageDeltaEventPayload"
    private const val EXECUTION_STARTED_PAYLOAD = "BackendExecutionStartedEventPayload"
    private const val EXECUTION_FINISHED_PAYLOAD = "BackendExecutionFinishedEventPayload"
    private const val EXECUTION_FAILED_PAYLOAD = "BackendExecutionFailedEventPayload"
    private const val EXECUTION_CANCELLED_PAYLOAD = "BackendExecutionCancelledEventPayload"
    private const val TOOL_CALL_STARTED_PAYLOAD = "BackendToolCallStartedEventPayload"
    private const val TOOL_CALL_FINISHED_PAYLOAD = "BackendToolCallFinishedEventPayload"
    private const val TOOL_CALL_FAILED_PAYLOAD = "BackendToolCallFailedEventPayload"
    private const val OPTION_REQUESTED_PAYLOAD = "BackendOptionRequestedEventPayload"
    private const val OPTION_ANSWERED_PAYLOAD = "BackendOptionAnsweredEventPayload"
    private const val OPTION_ITEM = "BackendEventOptionItem"

    private val durableVariants = listOf(
        DurableVariant("message.created", "BackendMessageCreatedEventEnvelope", MESSAGE_CREATED_PAYLOAD),
        DurableVariant("message.completed", "BackendMessageCompletedEventEnvelope", MESSAGE_COMPLETED_PAYLOAD),
        DurableVariant("execution.started", "BackendExecutionStartedEventEnvelope", EXECUTION_STARTED_PAYLOAD),
        DurableVariant("execution.finished", "BackendExecutionFinishedEventEnvelope", EXECUTION_FINISHED_PAYLOAD),
        DurableVariant("execution.failed", "BackendExecutionFailedEventEnvelope", EXECUTION_FAILED_PAYLOAD),
        DurableVariant("execution.cancelled", "BackendExecutionCancelledEventEnvelope", EXECUTION_CANCELLED_PAYLOAD),
        DurableVariant("tool.call.started", "BackendToolCallStartedEventEnvelope", TOOL_CALL_STARTED_PAYLOAD),
        DurableVariant("tool.call.finished", "BackendToolCallFinishedEventEnvelope", TOOL_CALL_FINISHED_PAYLOAD),
        DurableVariant("tool.call.failed", "BackendToolCallFailedEventEnvelope", TOOL_CALL_FAILED_PAYLOAD),
        DurableVariant("option.requested", "BackendOptionRequestedEventEnvelope", OPTION_REQUESTED_PAYLOAD),
        DurableVariant("option.answered", "BackendOptionAnsweredEventEnvelope", OPTION_ANSWERED_PAYLOAD),
    )

    val replayResponse: JsonSchema = objectSchema(
        required = listOf("items"),
        properties = mapOf(
            "items" to value(arraySchema(schema(REPLAY_EVENT))),
        ),
        description = "Durable events in sequence order. Canonical events use typed variants; legacy or partial stored rows use the compatibility fallback. Newly produced message.delta events remain live-only.",
    )

    val components: Map<String, JsonSchema> = buildMap {
        put(MESSAGE_CREATED_PAYLOAD, messageCreatedPayload())
        put(MESSAGE_COMPLETED_PAYLOAD, messageCompletedPayload())
        put(MESSAGE_DELTA_PAYLOAD, messageDeltaPayload())
        put(EXECUTION_STARTED_PAYLOAD, executionStartedPayload())
        put(EXECUTION_FINISHED_PAYLOAD, executionFinishedPayload())
        put(EXECUTION_FAILED_PAYLOAD, executionFailedPayload())
        put(EXECUTION_CANCELLED_PAYLOAD, executionCancelledPayload())
        put(TOOL_CALL_STARTED_PAYLOAD, toolCallStartedPayload())
        put(TOOL_CALL_FINISHED_PAYLOAD, toolCallFinishedPayload())
        put(TOOL_CALL_FAILED_PAYLOAD, toolCallFailedPayload())
        put(OPTION_ITEM, optionItem())
        put(OPTION_REQUESTED_PAYLOAD, optionRequestedPayload())
        put(OPTION_ANSWERED_PAYLOAD, optionAnsweredPayload())
        durableVariants.forEach { variant ->
            put(variant.componentName, durableEnvelope(variant))
        }
        put(
            DURABLE_EVENT,
            JsonSchema(
                title = DURABLE_EVENT,
                description = "The 11 canonical durable event variants.",
                oneOf = durableVariants.map { schema(it.componentName) },
                discriminator = JsonSchemaDiscriminator(
                    propertyName = "type",
                    mapping = durableVariants.associate { variant ->
                        variant.type to "#/components/schemas/${variant.componentName}"
                    },
                ),
            ),
        )
        put(LEGACY_DURABLE_EVENT, legacyDurableEnvelope())
        put(
            REPLAY_EVENT,
            JsonSchema(
                title = REPLAY_EVENT,
                description = "A canonical durable event or a replay-compatible legacy/partial stored event.",
                oneOf = listOf(schema(DURABLE_EVENT), schema(LEGACY_DURABLE_EVENT)),
            ),
        )
        put(MESSAGE_DELTA_EVENT, messageDeltaEnvelope())
        put(REPLAY_RESPONSE, replayResponse)
    }

    private fun durableEnvelope(variant: DurableVariant): JsonSchema =
        objectSchema(
            required = listOf("seq", "durable", "chatId", "executionId", "type", "payload", "createdAt"),
            properties = mapOf(
                "seq" to value(sequenceSchema()),
                "durable" to value(singletonBoolean(true)),
                "chatId" to value(uuidSchema()),
                "executionId" to value(nullableUuidSchema()),
                "type" to value(singletonString(variant.type)),
                "payload" to schema(variant.payloadComponentName),
                "createdAt" to value(dateTimeSchema()),
            ),
            description = "Canonical ${variant.type} durable event.",
        )

    private fun legacyDurableEnvelope(): JsonSchema =
        JsonSchema(
            type = JsonType.OBJECT,
            title = LEGACY_DURABLE_EVENT,
            description = "Replay compatibility for legacy or partial stored durable events, including historically persisted message.delta rows. Canonical events are explicitly excluded.",
            required = listOf("seq", "durable", "chatId", "executionId", "type", "payload", "createdAt"),
            properties = mapOf(
                "seq" to value(positiveSequenceSchema()),
                "durable" to value(singletonBoolean(true)),
                "chatId" to value(uuidSchema()),
                "executionId" to value(nullableUuidSchema()),
                "type" to value(stringEnum(AgentEventType.entries.map { it.value })),
                "payload" to value(arbitraryObjectSchema("Legacy event payload with producer-specific or partial fields.")),
                "createdAt" to value(dateTimeSchema()),
            ),
            additionalProperties = AdditionalProperties.Allowed(false),
            not = schema(DURABLE_EVENT),
        )

    private fun messageDeltaEnvelope(): JsonSchema =
        objectSchema(
            required = listOf("seq", "durable", "chatId", "executionId", "type", "payload", "createdAt"),
            properties = mapOf(
                "seq" to value(nullableSequenceSchema()),
                "durable" to value(singletonBoolean(false)),
                "chatId" to value(uuidSchema()),
                "executionId" to value(nullableUuidSchema()),
                "type" to value(singletonString("message.delta")),
                "payload" to schema(MESSAGE_DELTA_PAYLOAD),
                "createdAt" to value(dateTimeSchema()),
            ),
            description = "Live-only message delta envelope reserved for a future explicitly documented stream.",
        )

    private fun messageCreatedPayload(): JsonSchema =
        objectSchema(
            required = listOf("messageId", "seq", "role", "content"),
            properties = mapOf(
                "messageId" to value(uuidSchema()),
                "seq" to value(sequenceSchema()),
                "role" to value(stringSchema()),
                "content" to value(stringSchema()),
                "clientMessageId" to value(stringSchema()),
            ),
        )

    private fun messageCompletedPayload(): JsonSchema =
        objectSchema(
            required = listOf("messageId", "seq", "role", "content"),
            properties = mapOf(
                "messageId" to value(uuidSchema()),
                "seq" to value(sequenceSchema()),
                "role" to value(stringSchema()),
                "content" to value(stringSchema()),
            ),
        )

    private fun messageDeltaPayload(): JsonSchema =
        objectSchema(
            required = listOf("messageId", "delta"),
            properties = mapOf(
                "messageId" to value(uuidSchema()),
                "delta" to value(stringSchema()),
            ),
        )

    private fun executionStartedPayload(): JsonSchema =
        objectSchema(
            required = listOf("executionId", "streamingMessages"),
            properties = mapOf(
                "executionId" to value(uuidSchema()),
                "userMessageId" to value(uuidSchema()),
                "model" to value(stringSchema()),
                "provider" to value(stringSchema()),
                "streamingMessages" to value(booleanSchema()),
            ),
        )

    private fun executionFinishedPayload(): JsonSchema =
        objectSchema(
            required = listOf("executionId", "status"),
            properties = mapOf(
                "executionId" to value(uuidSchema()),
                "assistantMessageId" to value(uuidSchema()),
                "status" to value(stringSchema()),
                "promptTokens" to value(nonNegativeIntSchema()),
                "completionTokens" to value(nonNegativeIntSchema()),
                "totalTokens" to value(nonNegativeIntSchema()),
                "precachedTokens" to value(nonNegativeIntSchema()),
            ),
            description = "Execution completion payload with token usage flattened into this object when available.",
        )

    private fun executionFailedPayload(): JsonSchema =
        objectSchema(
            required = listOf("executionId", "errorCode", "errorMessage"),
            properties = mapOf(
                "executionId" to value(uuidSchema()),
                "assistantMessageId" to value(uuidSchema()),
                "errorCode" to value(stringSchema()),
                "errorMessage" to value(stringSchema()),
            ),
        )

    private fun executionCancelledPayload(): JsonSchema =
        objectSchema(
            required = listOf("executionId"),
            properties = mapOf(
                "executionId" to value(uuidSchema()),
                "assistantMessageId" to value(uuidSchema()),
            ),
        )

    private fun toolCallStartedPayload(): JsonSchema =
        objectSchema(
            required = listOf("toolCallId", "name", "argumentKeys"),
            properties = mapOf(
                "toolCallId" to value(stringSchema()),
                "name" to value(stringSchema()),
                "argumentKeys" to value(arraySchema(value(stringSchema()), uniqueItems = true)),
                "argumentsPreview" to value(arbitraryJsonSchema("Redacted arbitrary-JSON argument preview.")),
            ),
        )

    private fun toolCallFinishedPayload(): JsonSchema =
        objectSchema(
            required = listOf("toolCallId", "name", "status"),
            properties = mapOf(
                "toolCallId" to value(stringSchema()),
                "name" to value(stringSchema()),
                "status" to value(singletonString("finished")),
                "durationMs" to value(nonNegativeLongSchema()),
                "resultPreview" to value(arbitraryJsonSchema("Redacted arbitrary-JSON result preview.")),
            ),
        )

    private fun toolCallFailedPayload(): JsonSchema =
        objectSchema(
            required = listOf("toolCallId", "name", "status", "error"),
            properties = mapOf(
                "toolCallId" to value(stringSchema()),
                "name" to value(stringSchema()),
                "status" to value(singletonString("failed")),
                "error" to value(stringSchema()),
                "durationMs" to value(nonNegativeLongSchema()),
            ),
        )

    private fun optionItem(): JsonSchema =
        objectSchema(
            required = listOf("id", "label", "content"),
            properties = mapOf(
                "id" to value(stringSchema()),
                "label" to value(stringSchema()),
                "content" to value(nullableStringSchema()),
            ),
        )

    private fun optionRequestedPayload(): JsonSchema =
        objectSchema(
            required = listOf("optionId", "kind", "title", "selectionMode", "options"),
            properties = mapOf(
                "optionId" to value(uuidSchema()),
                "kind" to value(stringSchema()),
                "title" to value(stringSchema(description = "Empty when the producer supplied no title.")),
                "selectionMode" to value(stringSchema()),
                "options" to value(arraySchema(schema(OPTION_ITEM))),
            ),
        )

    private fun optionAnsweredPayload(): JsonSchema =
        objectSchema(
            required = listOf("optionId", "status", "selectedOptionIds", "freeText", "metadata"),
            properties = mapOf(
                "optionId" to value(uuidSchema()),
                "status" to value(stringSchema()),
                "selectedOptionIds" to value(arraySchema(value(stringSchema()), minItems = 1)),
                "freeText" to value(stringSchema(description = "Empty when the producer supplied no free text.")),
                "metadata" to value(stringMapSchema()),
            ),
        )

    private data class DurableVariant(
        val type: String,
        val componentName: String,
        val payloadComponentName: String,
    )
}

private fun objectSchema(
    required: List<String>,
    properties: Map<String, ReferenceOr<JsonSchema>>,
    description: String? = null,
): JsonSchema =
    JsonSchema(
        type = JsonType.OBJECT,
        description = description,
        required = required,
        properties = properties,
        additionalProperties = AdditionalProperties.Allowed(false),
    )

private fun stringSchema(description: String? = null): JsonSchema =
    JsonSchema(type = JsonType.STRING, description = description)

private fun nullableStringSchema(): JsonSchema =
    JsonSchema(type = JsonSchema.SchemaType.AnyOf(listOf(JsonType.STRING, JsonType.NULL)))

private fun uuidSchema(): JsonSchema = JsonSchema(type = JsonType.STRING, format = "uuid")

private fun nullableUuidSchema(): JsonSchema =
    JsonSchema(
        type = JsonSchema.SchemaType.AnyOf(listOf(JsonType.STRING, JsonType.NULL)),
        format = "uuid",
    )

private fun dateTimeSchema(): JsonSchema = JsonSchema(type = JsonType.STRING, format = "date-time")

private fun booleanSchema(): JsonSchema = JsonSchema(type = JsonType.BOOLEAN)

private fun sequenceSchema(): JsonSchema =
    JsonSchema(type = JsonType.INTEGER, format = "int64", minimum = 0.0)

private fun positiveSequenceSchema(): JsonSchema =
    JsonSchema(type = JsonType.INTEGER, format = "int64", minimum = 1.0)

private fun nullableSequenceSchema(): JsonSchema =
    JsonSchema(
        type = JsonSchema.SchemaType.AnyOf(listOf(JsonType.INTEGER, JsonType.NULL)),
        format = "int64",
        minimum = 0.0,
    )

private fun nonNegativeIntSchema(): JsonSchema =
    JsonSchema(type = JsonType.INTEGER, format = "int32", minimum = 0.0)

private fun nonNegativeLongSchema(): JsonSchema =
    JsonSchema(type = JsonType.INTEGER, format = "int64", minimum = 0.0)

private fun singletonString(value: String): JsonSchema =
    JsonSchema(type = JsonType.STRING, enum = listOf(GenericElement(value)))

private fun stringEnum(values: List<String>): JsonSchema =
    JsonSchema(type = JsonType.STRING, enum = values.map(::GenericElement))

private fun singletonBoolean(value: Boolean): JsonSchema =
    JsonSchema(type = JsonType.BOOLEAN, enum = listOf(GenericElement(value)))

private fun arraySchema(
    items: ReferenceOr<JsonSchema>,
    minItems: Int? = null,
    uniqueItems: Boolean? = null,
): JsonSchema =
    JsonSchema(
        type = JsonType.ARRAY,
        items = items,
        minItems = minItems,
        uniqueItems = uniqueItems,
    )

private fun stringMapSchema(): JsonSchema =
    JsonSchema(
        type = JsonType.OBJECT,
        additionalProperties = AdditionalProperties.PSchema(value(stringSchema())),
    )

private fun arbitraryJsonSchema(description: String): JsonSchema =
    JsonSchema(description = description)

private fun arbitraryObjectSchema(description: String): JsonSchema =
    JsonSchema(
        type = JsonType.OBJECT,
        description = description,
        additionalProperties = AdditionalProperties.Allowed(true),
    )

private fun schema(name: String): ReferenceOr<JsonSchema> = ReferenceOr.schema(name)

private fun value(schema: JsonSchema): ReferenceOr<JsonSchema> = ReferenceOr.Value(schema)
