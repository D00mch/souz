package ru.souz.backend.common

import ru.souz.llms.LLMModel
import ru.souz.llms.LlmProvider

object BackendLlmSupport {
    const val GIGA_UNSUPPORTED_MESSAGE: String = "Giga is not supported by the backend."

    val chatProviders: Set<LlmProvider> = setOf(
        LlmProvider.QWEN,
        LlmProvider.AI_TUNNEL,
        LlmProvider.ANTHROPIC,
        LlmProvider.OPENAI,
        LlmProvider.LOCAL,
        LlmProvider.CODEX,
    )

    val chatModels: Set<LLMModel> =
        LLMModel.entries.filterTo(linkedSetOf()) { it.provider in chatProviders }

    val fallbackChatModel: LLMModel = LLMModel.QwenFlash

    val userManagedKeyProviders: Set<LlmProvider> = setOf(
        LlmProvider.QWEN,
        LlmProvider.AI_TUNNEL,
        LlmProvider.ANTHROPIC,
        LlmProvider.OPENAI,
    )

    val embeddingProviders: Set<LlmProvider> = setOf(
        LlmProvider.QWEN,
        LlmProvider.AI_TUNNEL,
        LlmProvider.OPENAI,
        LlmProvider.LOCAL,
    )
}
