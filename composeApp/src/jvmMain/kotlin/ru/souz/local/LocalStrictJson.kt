package ru.souz.local

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import java.util.UUID
import ru.souz.giga.GigaMessageRole
import ru.souz.giga.GigaResponse
import ru.souz.giga.gigaJsonMapper

object LocalStrictJsonContract {
    val grammar: String = """
        root ::= ws response ws
        response ::= final-response | tool-calls-response
        final-response ::= "{" ws "\"type\"" ws ":" ws "\"final\"" ws "," ws "\"content\"" ws ":" ws string ws "}"
        tool-calls-response ::= "{" ws "\"type\"" ws ":" ws "\"tool_calls\"" ws "," ws "\"calls\"" ws ":" ws call-array ws "}"
        call-array ::= "[" ws call-object (ws "," ws call-object)* ws "]"
        call-object ::= "{" ws "\"id\"" ws ":" ws string ws "," ws "\"name\"" ws ":" ws string ws "," ws "\"arguments\"" ws ":" ws object ws "}"
        value ::= string | object | array | number | "true" | "false" | "null"
        object ::= "{" ws (string ws ":" ws value (ws "," ws string ws ":" ws value)*)? ws "}"
        array ::= "[" ws (value (ws "," ws value)*)? ws "]"
        string ::= "\"" char* "\""
        char ::= [^"\\\u0000-\u001F] | escape
        escape ::= "\\" (["\\/bfnrt] | "u" hex hex hex hex)
        number ::= "-"? int frac? exp?
        int ::= "0" | [1-9] [0-9]*
        frac ::= "." [0-9]+
        exp ::= [eE] [+-]? [0-9]+
        hex ::= [0-9a-fA-F]
        ws ::= [ \n\r\t]*
    """.trimIndent()

    fun instructions(toolsJson: String): String = buildString {
        appendLine("Return exactly one JSON object and nothing else.")
        appendLine("""Allowed responses only:""")
        appendLine("""1. {"type":"final","content":"<reply>"}""")
        appendLine("""2. {"type":"tool_calls","calls":[{"id":"call_1","name":"ToolName","arguments":{}}]}""")
        appendLine("Rules:")
        appendLine("- Never emit markdown, comments, XML, or prose outside the JSON object.")
        appendLine("- Never emit control tokens or wrappers such as <|...|>, <tool_call>, <tool_result>, or <think>.")
        appendLine("- In a final response, \"content\" must be plain human-readable text, not another JSON object like {\"result\":\"...\"}.")
        appendLine("- Use \"tool_calls\" when any tool is required before answering.")
        appendLine("- Tool call ids must be unique within the response.")
        appendLine("- Each object inside \"calls\" may contain only \"id\", \"name\", and \"arguments\".")
        appendLine("- Tool arguments must be valid JSON objects that match the tool schema.")
        appendLine("- Inside \"arguments\", include only concrete argument values. Never copy schema keys like \"type\", \"properties\", \"required\", \"description\", or \"enum\".")
        if (toolsJson.isNotBlank()) {
            appendLine()
            appendLine("Active tools:")
            appendLine(toolsJson)
        }
    }
}

class LocalStrictJsonParser {
    fun parse(
        rawText: String,
        requestModel: String,
        usage: GigaResponse.Usage,
        created: Long = System.currentTimeMillis(),
    ): GigaResponse.Chat {
        val trimmed = normalizeRawText(rawText)
        val node = runCatching { gigaJsonMapper.readTree(trimmed) }
            .getOrElse { error ->
                tryRecoverMalformedToolCalls(trimmed)?.let { recovered ->
                    return parseToolCalls(recovered, requestModel, usage, created)
                }
                if (shouldTreatAsPlainTextFinal(trimmed)) {
                    return plainTextFinal(
                        text = trimmed,
                        requestModel = requestModel,
                        usage = usage,
                        created = created,
                    )
                }
                return GigaResponse.Chat.Error(
                    status = -1,
                    message = "Local provider returned invalid JSON: ${error.message}. Raw preview: ${preview(trimmed)}",
                )
            }

        val type = node["type"]?.asText()
        if (type == null) {
            if (looksLikeSingleToolCall(node)) {
                return parseToolCalls(
                    node = gigaJsonMapper.readTree(
                        gigaJsonMapper.writeValueAsString(
                            mapOf(
                                "type" to "tool_calls",
                                "calls" to listOf(node),
                            )
                        )
                    ),
                    requestModel = requestModel,
                    usage = usage,
                    created = created,
                )
            }
            if (looksLikeResultObject(node)) {
                return plainTextFinal(
                    text = node["result"].asText(),
                    requestModel = requestModel,
                    usage = usage,
                    created = created,
                )
            }
            return GigaResponse.Chat.Error(
                -1,
                "Local provider JSON does not contain the required \"type\" field. Raw preview: ${preview(trimmed)}",
            )
        }

        return when (type) {
            "final" -> parseFinal(node, requestModel, usage, created)
            "tool_calls" -> parseToolCalls(node, requestModel, usage, created)
            else -> GigaResponse.Chat.Error(-1, "Local provider JSON has unsupported type: $type")
        }
    }

    private fun normalizeRawText(rawText: String): String {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return trimmed
        val withoutControlTokens = CONTROL_TOKEN_REGEX.replace(trimmed, " ").trim()
        return extractFirstJsonObject(withoutControlTokens) ?: withoutControlTokens
    }

    private fun looksLikeSingleToolCall(node: JsonNode): Boolean =
        node.isObject &&
            node["id"]?.isTextual == true &&
            node["name"]?.isTextual == true &&
            node["arguments"]?.isObject == true

    private fun looksLikeResultObject(node: JsonNode): Boolean =
        node.isObject &&
            node["result"]?.isTextual == true

    private fun extractFirstJsonObject(text: String): String? {
        var start = -1
        var depth = 0
        var inString = false
        var escaping = false

        text.forEachIndexed { index, ch ->
            if (start < 0) {
                if (ch == '{') {
                    start = index
                    depth = 1
                }
                return@forEachIndexed
            }

            if (escaping) {
                escaping = false
                return@forEachIndexed
            }

            if (inString) {
                when (ch) {
                    '\\' -> escaping = true
                    '"' -> inString = false
                }
                return@forEachIndexed
            }

            when (ch) {
                '"' -> inString = true
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        return text.substring(start, index + 1)
                    }
                }
            }
        }

        return null
    }

    private fun tryRecoverMalformedToolCalls(text: String): JsonNode? {
        if (!text.contains("\"tool_calls\"")) {
            return null
        }

        val callsMarkerIndex = text.indexOf("\"calls\"")
        if (callsMarkerIndex < 0) {
            return null
        }

        var searchFrom = callsMarkerIndex
        val recoveredCalls = mutableListOf<Map<String, Any>>()
        while (true) {
            val callStart = text.indexOf('{', searchFrom)
            if (callStart < 0) {
                break
            }

            val id = extractQuotedField(text, "id", callStart)
            val name = extractQuotedField(text, "name", callStart)
            val argumentsStart = locateFieldValueStart(text, "arguments", callStart)

            if (id == null || name == null || argumentsStart == null || argumentsStart >= text.length || text[argumentsStart] != '{') {
                searchFrom = callStart + 1
                continue
            }

            val argumentsJson = extractBalancedObject(text, argumentsStart)
            if (argumentsJson == null) {
                searchFrom = argumentsStart + 1
                continue
            }

            val argumentsNode = runCatching { gigaJsonMapper.readTree(argumentsJson) }.getOrNull()
            if (argumentsNode == null || !argumentsNode.isObject) {
                searchFrom = argumentsStart + 1
                continue
            }

            recoveredCalls += mapOf(
                "id" to id,
                "name" to name,
                "arguments" to gigaJsonMapper.convertValue(argumentsNode),
            )
            searchFrom = argumentsStart + argumentsJson.length
        }

        if (recoveredCalls.isEmpty()) {
            return null
        }

        val recovered = mapOf(
            "type" to "tool_calls",
            "calls" to recoveredCalls,
        )
        return runCatching { gigaJsonMapper.readTree(gigaJsonMapper.writeValueAsString(recovered)) }.getOrNull()
    }

    private fun extractQuotedField(text: String, fieldName: String, startIndex: Int): String? {
        val regex = Regex(""""$fieldName"\s*:\s*"((?:\\.|[^"\\])*)"""")
        return regex.find(text, startIndex)?.groupValues?.getOrNull(1)
            ?.replace("\\\"", "\"")
            ?.replace("\\\\", "\\")
    }

    private fun locateFieldValueStart(text: String, fieldName: String, startIndex: Int): Int? {
        val regex = Regex(""""$fieldName"\s*:\s*""")
        val match = regex.find(text, startIndex) ?: return null
        return match.range.last + 1
    }

    private fun extractBalancedObject(text: String, startIndex: Int): String? {
        if (startIndex >= text.length || text[startIndex] != '{') {
            return null
        }

        var depth = 0
        var inString = false
        var escaping = false

        for (index in startIndex until text.length) {
            val ch = text[index]
            if (escaping) {
                escaping = false
                continue
            }

            if (inString) {
                when (ch) {
                    '\\' -> escaping = true
                    '"' -> inString = false
                }
                continue
            }

            when (ch) {
                '"' -> inString = true
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        return text.substring(startIndex, index + 1)
                    }
                }
            }
        }

        return null
    }

    private fun preview(text: String, limit: Int = 160): String =
        text.replace("\n", "\\n")
            .replace("\r", "\\r")
            .take(limit)

    private fun shouldTreatAsPlainTextFinal(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        return trimmed.first() != '{' && trimmed.first() != '['
    }

    private fun plainTextFinal(
        text: String,
        requestModel: String,
        usage: GigaResponse.Usage,
        created: Long,
    ): GigaResponse.Chat = GigaResponse.Chat.Ok(
        choices = listOf(
            GigaResponse.Choice(
                message = GigaResponse.Message(
                    content = text.trim(),
                    role = GigaMessageRole.assistant,
                    functionCall = null,
                    functionsStateId = null,
                ),
                index = 0,
                finishReason = GigaResponse.FinishReason.stop,
            )
        ),
        created = created,
        model = requestModel,
        usage = usage,
    )

    private companion object {
        val CONTROL_TOKEN_REGEX = Regex("""<\|.*?\|>""")
    }

    private fun parseFinal(
        node: JsonNode,
        requestModel: String,
        usage: GigaResponse.Usage,
        created: Long,
    ): GigaResponse.Chat {
        val content = node["content"]?.takeIf(JsonNode::isTextual)?.asText()
            ?: return GigaResponse.Chat.Error(-1, "Local provider final JSON must contain string field \"content\".")

        return GigaResponse.Chat.Ok(
            choices = listOf(
                GigaResponse.Choice(
                    message = GigaResponse.Message(
                        content = unwrapEmbeddedResultObject(content),
                        role = GigaMessageRole.assistant,
                        functionCall = null,
                        functionsStateId = null,
                    ),
                    index = 0,
                    finishReason = GigaResponse.FinishReason.stop,
                )
            ),
            created = created,
            model = requestModel,
            usage = usage,
        )
    }

    private fun unwrapEmbeddedResultObject(content: String): String {
        val trimmed = content.trim()
        val embeddedNode = runCatching { gigaJsonMapper.readTree(trimmed) }.getOrNull()
            ?: return content
        return if (looksLikeResultObject(embeddedNode)) {
            embeddedNode["result"].asText()
        } else {
            content
        }
    }

    private fun parseToolCalls(
        node: JsonNode,
        requestModel: String,
        usage: GigaResponse.Usage,
        created: Long,
    ): GigaResponse.Chat {
        val callsNode = node["calls"]
        if (callsNode == null || !callsNode.isArray || callsNode.isEmpty) {
            return GigaResponse.Chat.Error(-1, "Local provider tool_calls JSON must contain a non-empty array in \"calls\".")
        }

        val choices = callsNode.mapIndexed { index, callNode ->
            val name = callNode["name"]?.takeIf(JsonNode::isTextual)?.asText()
                ?: return GigaResponse.Chat.Error(-1, "Local provider tool call #$index is missing string field \"name\".")
            val id = callNode["id"]?.takeIf(JsonNode::isTextual)?.asText()
                ?: return GigaResponse.Chat.Error(-1, "Local provider tool call #$index is missing string field \"id\".")
            val argumentsNode = callNode["arguments"]
                ?: return GigaResponse.Chat.Error(-1, "Local provider tool call #$index is missing field \"arguments\".")
            if (!argumentsNode.isObject) {
                return GigaResponse.Chat.Error(-1, "Local provider tool call #$index must use an object in \"arguments\".")
            }

            GigaResponse.Choice(
                message = GigaResponse.Message(
                    content = "",
                    role = GigaMessageRole.assistant,
                    functionCall = GigaResponse.FunctionCall(
                        name = name,
                        arguments = gigaJsonMapper.convertValue(argumentsNode),
                    ),
                    functionsStateId = id.ifBlank { "call_${UUID.randomUUID()}" },
                ),
                index = index,
                finishReason = GigaResponse.FinishReason.function_call,
            )
        }

        return GigaResponse.Chat.Ok(
            choices = choices,
            created = created,
            model = requestModel,
            usage = usage,
        )
    }
}
