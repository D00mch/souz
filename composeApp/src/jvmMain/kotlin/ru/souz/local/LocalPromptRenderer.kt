package ru.souz.local

import com.fasterxml.jackson.module.kotlin.readValue
import ru.souz.giga.GigaMessageRole
import ru.souz.giga.GigaRequest
import ru.souz.giga.gigaJsonMapper

class LocalPromptRenderer {
    fun render(body: GigaRequest.Chat, profile: LocalModelProfile): String {
        val systemPrompt = buildSystemPrompt(body)
        val messages = body.messages.filterNot { it.role == GigaMessageRole.system }
            .map(::toRenderedMessage)

        return when (profile.templateFamily) {
            LocalTemplateFamily.QWEN3 -> renderQwen(systemPrompt, messages)
            LocalTemplateFamily.LLAMA_3_1 -> renderLlama(systemPrompt, messages)
            LocalTemplateFamily.GIGACHAT_3_1 -> renderGigaChat(systemPrompt, messages)
        }
    }

    private fun buildSystemPrompt(body: GigaRequest.Chat): String {
        val explicitSystem = body.messages
            .filter { it.role == GigaMessageRole.system }
            .joinToString("\n\n") { it.content.trim() }
            .trim()

        val contract = LocalStrictJsonContract.instructions(renderToolGuidance(body.functions))
        return listOf(explicitSystem, contract)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
    }

    private fun renderToolGuidance(functions: List<GigaRequest.Function>): String {
        if (functions.isEmpty()) {
            return ""
        }

        return buildString {
            functions.forEach { fn ->
                append("- ")
                append(fn.name)
                append(": ")
                append(fn.description.trim())
                appendLine()

                val sortedArguments = fn.parameters.properties.entries.sortedBy { (name, _) ->
                    if (name in fn.parameters.required) 0 else 1
                }
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
                        append(gigaJsonMapper.writeValueAsString(example.params))
                        appendLine()
                    }

                appendLine()
            }
        }.trim()
    }

    private fun toRenderedMessage(message: GigaRequest.Message): RenderedMessage = when (message.role) {
        GigaMessageRole.user -> RenderedMessage(role = "user", content = message.content.trim())
        GigaMessageRole.assistant -> RenderedMessage(
            role = "assistant",
            content = renderAssistantMessage(message),
        )

        GigaMessageRole.function -> RenderedMessage(
            role = "user",
            content = gigaJsonMapper.writeValueAsString(
                mapOf(
                    "tool_result" to mapOf(
                        "tool_name" to message.name.orEmpty(),
                        "tool_call_id" to message.functionsStateId.orEmpty(),
                        "content" to message.content,
                    )
                )
            ),
        )

        GigaMessageRole.system -> error("System messages must be handled separately before rendering.")
    }

    private fun renderAssistantMessage(message: GigaRequest.Message): String {
        val toolCallJson = message.functionsStateId?.let {
            runCatching { gigaJsonMapper.readValue<Map<String, Any>>(message.content) }.getOrNull()
        }
        if (toolCallJson == null) {
            return message.content.trim()
        }

        val toolName = toolCallJson["name"]?.toString().orEmpty()
        val arguments = toolCallJson["arguments"]
        return gigaJsonMapper.writeValueAsString(
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

    private fun renderLlama(systemPrompt: String, messages: List<RenderedMessage>): String = buildString {
        append("<|begin_of_text|>")
        if (systemPrompt.isNotBlank()) {
            append(header("system"))
            append(systemPrompt)
            append("<|eot_id|>")
        }
        messages.forEach { message ->
            append(header(message.role))
            append(message.content)
            append("<|eot_id|>")
        }
        append(header("assistant"))
    }

    private fun renderGigaChat(systemPrompt: String, messages: List<RenderedMessage>): String = buildString {
        append("<s>")
        if (systemPrompt.isNotBlank()) {
            append(systemPrompt)
            append("<|message_sep|>")
        }
        messages.forEach { message ->
            when (message.role) {
                "user" -> {
                    append("user<|role_sep|>")
                    append(message.content)
                    append("<|message_sep|>")
                    append("available functions<|role_sep|>[]<|message_sep|>")
                }

                "assistant" -> {
                    append("assistant<|role_sep|>")
                    append(message.content)
                    append("<|message_sep|>")
                }

                else -> {
                    append(message.role)
                    append("<|role_sep|>")
                    append(message.content)
                    append("<|message_sep|>")
                }
            }
        }
        append("assistant<|role_sep|>")
    }

    private fun header(role: String): String =
        "<|start_header_id|>$role<|end_header_id|>\n\n"

    private data class RenderedMessage(
        val role: String,
        val content: String,
    )
}
