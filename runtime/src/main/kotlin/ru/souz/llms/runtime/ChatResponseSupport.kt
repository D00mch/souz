package ru.souz.llms.runtime

import ru.souz.llms.LLMResponse

internal fun LLMResponse.Chat.requireAssistantText(prefix: String): String = when (this) {
    is LLMResponse.Chat.Ok -> choices.firstOrNull()?.message?.content
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: throw IllegalStateException("$prefix: empty response")

    is LLMResponse.Chat.Error -> throw IllegalStateException("$prefix: $message")
}
