package ru.souz.agent.nodes

import ru.souz.llms.LLMRequest
import ru.souz.llms.restJsonMapper

internal const val CHAT_MAX_OUTPUT_TOKENS = 4_096
internal const val SUMMARIZATION_MAX_OUTPUT_TOKENS = 1_024
internal const val CLASSIFICATION_MAX_OUTPUT_TOKENS = 512
internal const val CONTEXT_SAFETY_MARGIN_TOKENS = 512

// Include message framing, tool schemas and call arguments as well as text.
internal fun LLMRequest.Chat.estimatedInputTokens(): Int =
    ((messages.sumOf { message ->
        16L + message.content.length + message.name.orEmpty().length +
            message.functionsStateId.orEmpty().length +
            (message.functionCall?.let { it.name.length.toLong() + it.arguments.length } ?: 0L)
    } + restJsonMapper.writeValueAsString(functions).length + 3) / 4)
        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

internal fun LLMRequest.Chat.inputBudget(contextSize: Int): Int =
    contextSize - maxTokens - CONTEXT_SAFETY_MARGIN_TOKENS

internal fun LLMRequest.Chat.exceedsContextBudget(contextSize: Int): Boolean =
    estimatedInputTokens() > inputBudget(contextSize)
