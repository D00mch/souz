package ru.souz.backend.agent.runtime

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.TextNode
import ru.souz.llms.restJsonMapper

internal class ToolCallPreviewer(
    private val mapper: ObjectMapper = restJsonMapper,
) {
    fun argumentsPreview(arguments: Any?): JsonNode =
        previewJson(arguments, placeholder = "[UNAVAILABLE_ARGUMENTS]")

    fun resultPreview(result: Any?): JsonNode =
        previewJson(result, placeholder = "[UNAVAILABLE_RESULT]")

    fun serializePreview(node: JsonNode): String =
        runCatching { mapper.writeValueAsString(node) }
            .getOrElse { mapper.writeValueAsString("[UNAVAILABLE_PREVIEW]") }

    fun safeErrorPreview(error: Throwable): String {
        val type = error::class.simpleName ?: "ToolExecutionFailed"
        val message = sanitizeText(error.message.orEmpty()).trim()
        return truncateText(
            if (message.isBlank()) type else "$type: $message",
            maxLength = MAX_ERROR_LENGTH,
        )
    }

    private fun previewJson(
        value: Any?,
        placeholder: String,
    ): JsonNode {
        val rawNode = runCatching { toJsonNode(value) }
            .getOrElse { TextNode.valueOf(placeholder) }
        val sanitized = sanitizeNode(rawNode, depth = 0)
        val serialized = runCatching { mapper.writeValueAsString(sanitized) }.getOrNull()
        return if (serialized != null && serialized.length > MAX_SERIALIZED_PREVIEW_LENGTH) {
            TextNode.valueOf(truncateText(serialized, MAX_SERIALIZED_PREVIEW_LENGTH))
        } else {
            sanitized
        }
    }

    private fun toJsonNode(value: Any?): JsonNode =
        when (value) {
            null -> JsonNodeFactory.instance.nullNode()
            is JsonNode -> value // Sanitization reads the input and builds fresh containers.
            is String -> runCatching { mapper.readTree(value) }.getOrElse { TextNode.valueOf(value) }
            else -> mapper.valueToTree(value)
        }

    private fun sanitizeNode(node: JsonNode, depth: Int): JsonNode =
        when {
            depth >= MAX_DEPTH -> TextNode.valueOf("[TRUNCATED]")
            node.isObject -> JsonNodeFactory.instance.objectNode().apply {
                val fields = node.properties().iterator()
                repeat(minOf(node.size(), MAX_OBJECT_FIELDS)) {
                    val (key, value) = fields.next()
                    set<JsonNode>(
                        key,
                        if (isSensitiveKey(key)) TextNode.valueOf(REDACTED) else sanitizeNode(value, depth + 1),
                    )
                }
                if (node.size() > MAX_OBJECT_FIELDS) {
                    put("_truncated", "${node.size() - MAX_OBJECT_FIELDS} more fields")
                }
            }
            node.isArray -> JsonNodeFactory.instance.arrayNode().apply {
                repeat(minOf(node.size(), MAX_ARRAY_ITEMS)) { index ->
                    add(sanitizeNode(node[index], depth + 1))
                }
                if (node.size() > MAX_ARRAY_ITEMS) {
                    add("[TRUNCATED ${node.size() - MAX_ARRAY_ITEMS} more items]")
                }
            }
            node.isNumber || node.isBoolean || node.isNull -> node
            else -> TextNode.valueOf(truncateText(sanitizeText(node.asText()), MAX_STRING_LENGTH))
        }

    private fun isSensitiveKey(key: String): Boolean =
        key.lowercase().replace("-", "").replace("_", "") in SENSITIVE_KEYS

    private fun sanitizeText(value: String): String {
        if (value.isBlank()) return value
        return TEXT_REDACTIONS.fold(value) { acc, regex ->
            acc.replace(regex, REDACTED)
        }.replace(BEARER_VALUE_REGEX) { "${it.groupValues[1]}[REDACTED]" }
            .replace(KEY_VALUE_REGEX) { "${it.groupValues[1]}=[REDACTED]" }
    }

    private fun truncateText(value: String, maxLength: Int): String =
        if (value.length <= maxLength) value else value.take(maxLength - 3) + "..."
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
