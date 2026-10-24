package ru.souz.llms.runtime

import ru.souz.llms.LLMResponse

/**
 * Normalizes provider chat responses for internal gateway adapters that only need plain assistant text.
 *
 * Vision gateways share the same failure mapping, so keeping it here avoids duplicating the
 * "empty response vs provider error" handling in each provider-specific implementation.
 */
internal fun LLMResponse.Chat.requireAssistantText(prefix: String): String = when (this) {
    is LLMResponse.Chat.Ok -> choices.firstOrNull()?.message?.content
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: throw IllegalStateException("$prefix: empty response")

    is LLMResponse.Chat.Error -> throw IllegalStateException("$prefix: $message")
}
