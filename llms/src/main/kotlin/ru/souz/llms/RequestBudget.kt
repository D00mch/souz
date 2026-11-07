package ru.souz.llms

// Include message framing, tool schemas and call arguments as well as text.
fun LLMRequest.Chat.estimatedInputTokens(): Int =
    ((messages.sumOf { message ->
        16L + message.content.length + message.name.orEmpty().length +
            message.functionsStateId.orEmpty().length +
            (message.functionCall?.let { it.name.length.toLong() + it.arguments.length } ?: 0L)
    } + restJsonMapper.writeValueAsString(functions).length + 3) / 4)
        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

fun LLMRequest.Chat.exceedsContextBudget(contextSize: Int): Boolean =
    estimatedInputTokens() > contextSize * 0.8
