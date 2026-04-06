package ru.souz.llms.local

import com.fasterxml.jackson.module.kotlin.readValue
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.restJsonMapper

private const val CLASSIFICATION_RESPONSE_FORMAT_MARKER = "CATEGORY1,CATEGORY2 0-100"

internal fun LLMRequest.Chat.prefersPlainTextLocalOutput(): Boolean {
    if (functions.isNotEmpty()) return false

    return messages.any { message ->
        message.role == LLMMessageRole.system &&
            message.content.contains(CLASSIFICATION_RESPONSE_FORMAT_MARKER)
    }
}

class LocalPromptRenderer {
    fun render(body: LLMRequest.Chat, profile: LocalModelProfile): String {
        val contextBudget = resolveEffectiveContextBudget(body, profile)
        val systemPrompt = buildSystemPrompt(body, profile, contextBudget)
        val messages = body.messages.filterNot { it.role == LLMMessageRole.system }
            .map { toRenderedMessage(it, profile, contextBudget) }

        return when (profile.promptFamily) {
            LocalPromptFamily.QWEN_CHATML -> renderQwen(systemPrompt, messages)
            LocalPromptFamily.GEMMA4 -> renderGemma4(systemPrompt, messages)
        }
    }

    private fun buildSystemPrompt(body: LLMRequest.Chat, profile: LocalModelProfile, contextBudget: Int): String {
        val explicitSystem = body.messages
            .filter { it.role == LLMMessageRole.system }
            .joinToString("\n\n") { it.content.trim() }
            .trim()

        val contract = if (body.prefersPlainTextLocalOutput()) {
            ""
        } else {
            LocalStrictJsonContract.instructions(renderToolGuidance(body.functions, contextBudget))
        }
        return listOf(explicitSystem, contract)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
    }

    private fun renderToolGuidance(
        functions: List<LLMRequest.Function>,
        contextBudget: Int,
    ): String {
        if (functions.isEmpty()) {
            return ""
        }

        val maxChars = when {
            contextBudget <= SMALL_CONTEXT_THRESHOLD -> SMALL_CONTEXT_TOOL_GUIDANCE_CHARS
            else -> LARGE_CONTEXT_TOOL_GUIDANCE_CHARS
        }
        val preferredModes = when {
            contextBudget <= SMALL_CONTEXT_THRESHOLD && functions.size > SMALL_CONTEXT_COMPACT_GUIDANCE_LIMIT ->
                listOf(ToolGuidanceMode.MINIMAL)

            contextBudget <= SMALL_CONTEXT_THRESHOLD && functions.size > FULL_GUIDANCE_LIMIT ->
                listOf(ToolGuidanceMode.COMPACT, ToolGuidanceMode.MINIMAL)

            functions.size > COMPACT_GUIDANCE_LIMIT ->
                listOf(ToolGuidanceMode.MINIMAL)

            functions.size > FULL_GUIDANCE_LIMIT ->
                listOf(ToolGuidanceMode.COMPACT, ToolGuidanceMode.MINIMAL)

            else ->
                listOf(ToolGuidanceMode.FULL, ToolGuidanceMode.COMPACT, ToolGuidanceMode.MINIMAL)
        }

        return preferredModes.asSequence()
            .map { mode -> renderToolGuidance(functions, mode) }
            .firstOrNull { rendered -> rendered.length <= maxChars }
            ?: renderToolGuidance(functions, ToolGuidanceMode.MINIMAL)
    }

    private fun renderToolGuidance(
        functions: List<LLMRequest.Function>,
        mode: ToolGuidanceMode,
    ): String = buildString {
        if (mode == ToolGuidanceMode.MINIMAL) {
            appendLine("Tool signatures: `!` required, `?` optional.")
        }

        functions.forEach { fn ->
            append("- ")
            append(fn.name)

            when (mode) {
                ToolGuidanceMode.FULL -> {
                    append(": ")
                    append(fn.description.trim())
                    appendLine()

                    val sortedArguments = sortArguments(fn)
                    if (sortedArguments.isNotEmpty()) {
                        appendLine("  Arguments:")
                        sortedArguments.forEach { (name, property) ->
                            append("  - ")
                            append(name)
                            append(" (")
                            append(property.type)
                            append(", ")
                            append(if (name in fn.parameters.required) "required" else "optional")
                            append(")")
                            property.description
                                ?.trim()
                                ?.takeIf(String::isNotBlank)
                                ?.let { description ->
                                    append(": ")
                                    append(description)
                                }
                            property.enum
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { enumValues ->
                                    append(" Allowed values: ")
                                    append(enumValues.joinToString(", "))
                                    append(".")
                                }
                            appendLine()
                        }
                    }

                    fn.fewShotExamples
                        ?.firstOrNull()
                        ?.let { example ->
                            append("  Example arguments JSON: ")
                            append(restJsonMapper.writeValueAsString(example.params))
                            appendLine()
                        }
                }

                ToolGuidanceMode.COMPACT -> {
                    append(renderCompactSignature(fn))
                    fn.description
                        .trim()
                        .takeIf(String::isNotBlank)
                        ?.let { description ->
                            append(": ")
                            append(description)
                        }
                    appendLine()
                }

                ToolGuidanceMode.MINIMAL -> {
                    append(renderMinimalSignature(fn))
                    appendLine()
                }
            }

            appendLine()
        }
    }.trim()

    private fun sortArguments(fn: LLMRequest.Function): List<Map.Entry<String, LLMRequest.Property>> =
        fn.parameters.properties.entries.sortedBy { (name, _) ->
            if (name in fn.parameters.required) 0 else 1
        }

    private fun renderCompactSignature(fn: LLMRequest.Function): String {
        val args = sortArguments(fn).joinToString(", ") { (name, property) ->
            buildString {
                append(name)
                append(":")
                append(renderPropertyType(property))
                append(if (name in fn.parameters.required) "!" else "?")
            }
        }
        return if (args.isBlank()) "()" else "($args)"
    }

    private fun renderMinimalSignature(fn: LLMRequest.Function): String {
        val args = sortArguments(fn).joinToString(", ") { (name, property) ->
            buildString {
                append(name)
                property.enum
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { enumValues ->
                        append("=")
                        append(enumValues.joinToString("|"))
                    }
                    ?: property.type
                        .takeIf { it.isNotBlank() && !it.equals("string", ignoreCase = true) }
                        ?.let { type ->
                            append(":")
                            append(type)
                        }
                append(if (name in fn.parameters.required) "!" else "?")
            }
        }
        return if (args.isBlank()) "()" else "($args)"
    }

    private fun renderPropertyType(property: LLMRequest.Property): String =
        property.enum
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString("|")
            ?: property.type

    private fun toRenderedMessage(
        message: LLMRequest.Message,
        profile: LocalModelProfile,
        contextBudget: Int,
    ): RenderedMessage = when (message.role) {
        LLMMessageRole.user -> RenderedMessage(role = "user", content = message.content.trim())
        LLMMessageRole.assistant -> RenderedMessage(
            role = "assistant",
            content = renderAssistantMessage(message),
        )

        LLMMessageRole.function -> RenderedMessage(
            role = "user",
            content = restJsonMapper.writeValueAsString(
                mapOf(
                    "tool_result" to mapOf(
                        "tool_name" to message.name.orEmpty(),
                        "tool_call_id" to message.functionsStateId.orEmpty(),
                        "content" to normalizeToolResultContent(message.content, contextBudget),
                    )
                )
            ),
        )

        LLMMessageRole.system -> error("System messages must be handled separately before rendering.")
    }

    private fun renderAssistantMessage(message: LLMRequest.Message): String {
        val toolCallJson = message.functionsStateId?.let {
            runCatching { restJsonMapper.readValue<Map<String, Any>>(message.content) }.getOrNull()
        }
        if (toolCallJson == null) {
            return message.content.trim()
        }

        val toolName = toolCallJson["name"]?.toString().orEmpty()
        val arguments = toolCallJson["arguments"]
        return restJsonMapper.writeValueAsString(
            mapOf(
                "type" to "tool_calls",
                "calls" to listOf(
                    mapOf(
                        "id" to message.functionsStateId,
                        "name" to toolName,
                        "arguments" to (arguments ?: emptyMap<String, Any>()),
                    )
                )
            )
        )
    }

    private fun renderQwen(systemPrompt: String, messages: List<RenderedMessage>): String = buildString {
        if (systemPrompt.isNotBlank()) {
            appendLine("<|im_start|>system")
            appendLine(systemPrompt)
            appendLine("<|im_end|>")
        }
        messages.forEach { message ->
            appendLine("<|im_start|>${message.role}")
            appendLine(message.content)
            appendLine("<|im_end|>")
        }
        append("<|im_start|>assistant\n")
    }

    private fun renderGemma4(systemPrompt: String, messages: List<RenderedMessage>): String = buildString {
        if (systemPrompt.isNotBlank()) {
            appendLine("<|turn>system")
            appendLine(systemPrompt)
            appendLine("<turn|>")
        }
        messages.forEach { message ->
            appendLine("<|turn>${message.role}")
            appendLine(message.content)
            appendLine("<turn|>")
        }
        append("<|turn>assistant\n")
    }

    private fun normalizeToolResultContent(rawContent: String, contextBudget: Int): Any {
        val decoded = runCatching { restJsonMapper.readValue<String>(rawContent) }.getOrDefault(rawContent)
        val budget = when {
            contextBudget <= SMALL_CONTEXT_THRESHOLD -> LOCAL_SMALL_CONTEXT_TOOL_PREVIEW_CHARS
            else -> LOCAL_LARGE_CONTEXT_TOOL_PREVIEW_CHARS
        }

        if (decoded.length > budget) {
            return mapOf(
                "truncated" to true,
                "original_length" to decoded.length,
                "preview" to decoded.take(budget).trimEnd() + TRUNCATION_SUFFIX,
            )
        }

        return runCatching { restJsonMapper.readValue<Any>(decoded) }.getOrDefault(decoded)
    }

    private fun resolveEffectiveContextBudget(body: LLMRequest.Chat, profile: LocalModelProfile): Int =
        if (body.maxTokens >= MIN_CONTEXT_THRESHOLD) {
            body.maxTokens
                .coerceAtLeast(MIN_CONTEXT_THRESHOLD)
                .coerceAtMost(profile.maxContextSize)
        } else {
            profile.defaultContextSize.coerceAtMost(profile.maxContextSize)
        }

    private data class RenderedMessage(
        val role: String,
        val content: String,
    )

    private enum class ToolGuidanceMode {
        FULL,
        COMPACT,
        MINIMAL,
    }

    private companion object {
        const val MIN_CONTEXT_THRESHOLD = 2_048
        const val SMALL_CONTEXT_THRESHOLD = 8_192
        const val FULL_GUIDANCE_LIMIT = 4
        const val SMALL_CONTEXT_COMPACT_GUIDANCE_LIMIT = 8
        const val COMPACT_GUIDANCE_LIMIT = 12
        const val SMALL_CONTEXT_TOOL_GUIDANCE_CHARS = 3_500
        const val LARGE_CONTEXT_TOOL_GUIDANCE_CHARS = 7_000
        const val LOCAL_SMALL_CONTEXT_TOOL_PREVIEW_CHARS = 3_500
        const val LOCAL_LARGE_CONTEXT_TOOL_PREVIEW_CHARS = 7_000
        const val TRUNCATION_SUFFIX = "\n...[truncated for local context window]..."
    }
}
