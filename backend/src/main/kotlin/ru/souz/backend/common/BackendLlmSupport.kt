package ru.souz.backend.common

import ru.souz.backend.llm.hasCompleteCodexOAuthCredentials
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmProvider

object BackendLlmSupport {
    const val GIGA_UNSUPPORTED_MESSAGE: String = "Giga is not supported by the backend."

    val chatProviders: Set<LlmProvider> = setOf(
        LlmProvider.QWEN,
        LlmProvider.AI_TUNNEL,
        LlmProvider.ANTHROPIC,
        LlmProvider.OPENAI,
        LlmProvider.CODEX,
    )

    val chatModels: Set<LLMModel> =
        LLMModel.entries.filterTo(linkedSetOf()) { it.provider in chatProviders }

    val fallbackChatModel: LLMModel = LLMModel.QwenMax

    internal val defaultModelOrder: List<LLMModel> = listOf(
        LLMModel.OpenAICompatibleCustom,
        LLMModel.CodexGpt54,
        LLMModel.OpenAIGpt5Mini,
        LLMModel.AnthropicHaiku45,
        LLMModel.AiTunnelClaudeHaiku,
        fallbackChatModel,
    )

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
    )
}

internal fun SettingsProvider.supportsChatModel(model: LLMModel): Boolean =
    model in BackendLlmSupport.chatModels && (model != LLMModel.OpenAICompatibleCustom || !openaiModel.isNullOrBlank())

internal fun SettingsProvider.hasServerChatAccess(provider: LlmProvider): Boolean =
    if (provider == LlmProvider.CODEX) hasCompleteCodexOAuthCredentials() else hasKey(provider)
