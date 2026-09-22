package ru.souz.backend.agent.runtime

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import ru.souz.llms.restJsonMapper

/** One sanitized tool preview: [node] for event payloads, [json] for `tool_calls` persistence. */
internal data class ToolCallPreview(
    val node: JsonNode,
    val json: String,
)

internal class ToolCallPreviewer(
    private val mapper: ObjectMapper = restJsonMapper,
) {
    fun argumentsPreview(arguments: Any?): ToolCallPreview =
        preview(arguments, placeholder = "[UNAVAILABLE_ARGUMENTS]")

    fun resultPreview(result: Any?): ToolCallPreview =
        preview(result, placeholder = "[UNAVAILABLE_RESULT]")

    fun safeErrorPreview(error: Throwable): String {
        val type = error::class.simpleName ?: "ToolExecutionFailed"
        val message = sanitizeText(error.message.orEmpty()).trim()
        return truncateText(
            if (message.isBlank()) type else "$type: $message",
            maxLength = MAX_ERROR_LENGTH,
        )
    }

    private fun preview(
        value: Any?,
        placeholder: String,
    ): ToolCallPreview {
        val rawNode = runCatching { toJsonNode(value) }
            .getOrElse { TextNode.valueOf(placeholder) }
        val sanitized = sanitizeNode(rawNode, depth = 0)
        val serialized = runCatching { mapper.writeValueAsString(sanitized) }.getOrNull()
            ?: return ToolCallPreview(sanitized, mapper.writeValueAsString("[UNAVAILABLE_PREVIEW]"))
        if (serialized.length <= MAX_SERIALIZED_PREVIEW_LENGTH) {
            return ToolCallPreview(sanitized, serialized)
        }
        val truncated = TextNode.valueOf(truncateText(serialized, MAX_SERIALIZED_PREVIEW_LENGTH))
        return ToolCallPreview(truncated, mapper.writeValueAsString(truncated))
    }

    // Sanitization always builds a fresh tree, so the input is only read and needs no defensive copy.
    private fun toJsonNode(value: Any?): JsonNode =
        when (value) {
            null -> JsonNodeFactory.instance.nullNode()
            is JsonNode -> value
            is String -> parseStringValue(value)
            else -> mapper.valueToTree(value)
        }

    private fun parseStringValue(value: String): JsonNode =
        runCatching { mapper.readTree(value) }
            .getOrElse { TextNode.valueOf(value) }

    private fun sanitizeNode(
        node: JsonNode,
        depth: Int,
    ): JsonNode {
        if (depth >= MAX_DEPTH) {
            return TextNode.valueOf("[TRUNCATED]")
        }
        return when {
            node.isObject -> sanitizeObject(node as ObjectNode, depth)
            node.isArray -> sanitizeArray(node as ArrayNode, depth)
            node.isTextual -> TextNode.valueOf(truncateText(sanitizeText(node.asText()), MAX_STRING_LENGTH))
            node.isNumber || node.isBoolean || node.isNull -> node
            else -> TextNode.valueOf(truncateText(sanitizeText(node.asText()), MAX_STRING_LENGTH))
        }
    }

    private fun sanitizeObject(
        node: ObjectNode,
        depth: Int,
    ): ObjectNode {
        val sanitized = JsonNodeFactory.instance.objectNode()
        for ((key, value) in node.fields().asSequence().take(MAX_OBJECT_FIELDS)) {
            sanitized.set<JsonNode>(
                key,
                if (isSensitiveKey(key)) {
                    TextNode.valueOf(REDACTED)
                } else {
                    sanitizeNode(value, depth + 1)
                },
            )
        }
        if (node.size() > MAX_OBJECT_FIELDS) {
            sanitized.put("_truncated", "${node.size() - MAX_OBJECT_FIELDS} more fields")
        }
        return sanitized
    }

    private fun sanitizeArray(
        node: ArrayNode,
        depth: Int,
    ): ArrayNode {
        val sanitized = JsonNodeFactory.instance.arrayNode()
        for (item in node.elements().asSequence().take(MAX_ARRAY_ITEMS)) {
            sanitized.add(sanitizeNode(item, depth + 1))
        }
        if (node.size() > MAX_ARRAY_ITEMS) {
            sanitized.add("[TRUNCATED ${node.size() - MAX_ARRAY_ITEMS} more items]")
        }
        return sanitized
    }

    private fun isSensitiveKey(key: String): Boolean =
        key.lowercase()
            .replace("-", "")
            .replace("_", "") in SENSITIVE_KEYS

    private fun sanitizeText(value: String): String {
        if (value.isBlank()) return value
        return TEXT_REDACTIONS.fold(value) { acc, regex ->
            acc.replace(regex, REDACTED)
        }.replace(BEARER_VALUE_REGEX) { "${it.groupValues[1]}[REDACTED]" }
            .replace(KEY_VALUE_REGEX) { "${it.groupValues[1]}=[REDACTED]" }
    }

    private fun truncateText(
        value: String,
        maxLength: Int,
    ): String =
        if (value.length <= maxLength) {
            value
        } else {
            value.take(maxLength - 3) + "..."
        }
}

private const val REDACTED = "[REDACTED]"
private const val MAX_DEPTH = 6
private const val MAX_OBJECT_FIELDS = 8
private const val MAX_ARRAY_ITEMS = 8
private const val MAX_STRING_LENGTH = 160
private const val MAX_ERROR_LENGTH = 240
private const val MAX_SERIALIZED_PREVIEW_LENGTH = 1_024

private val SENSITIVE_KEYS = setOf(
    "apikey",
    "token",
    "accesstoken",
    "refreshtoken",
    "authorization",
    "auth",
    "cookie",
    "password",
    "passwd",
    "secret",
    "clientsecret",
    "privatekey",
    "session",
    "credential",
    "credentials",
)

private val TEXT_REDACTIONS = listOf(
    Regex("""(?i)\bsk-[A-Za-z0-9._-]+\b"""),
    Regex("""(?i)\b(?:[A-Za-z0-9]+[-_])*(?:token|secret|password|passwd|cookie|session|credential|credentials)(?:[-_][A-Za-z0-9]+)*\b"""),
    Regex("""(?i)\b(?:[A-Za-z0-9]+[-_])*(?:private[-_]?key|api[-_]?key|client[-_]?secret)(?:[-_][A-Za-z0-9]+)*\b"""),
)

private val BEARER_VALUE_REGEX =
    Regex("""(?i)\b(Bearer\s+)[A-Za-z0-9._~+/=-]+\b""")

private val KEY_VALUE_REGEX =
    Regex("""(?i)\b(api[_-]?key|access[_-]?token|refresh[_-]?token|authorization|auth|cookie|password|passwd|secret|client[_-]?secret|private[_-]?key|session|credential|credentials|token)\b\s*[:=]\s*([^\s,;]+)""")
