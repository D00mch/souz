package ru.souz.llms.runtime

import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmProvider

/** Host-entry compatibility for requests without an explicit provider; adapters receive wire IDs. */
fun SettingsProvider.resolveLegacyChatModel(provider: LlmProvider, model: String): String {
    when (provider) {
        LlmProvider.AI_TUNNEL -> return if (
            model.equals("ai-tunnel", ignoreCase = true) || model.startsWith("GigaChat", ignoreCase = true)
        ) configuredModel("AITUNNEL_MODEL", "gpt-4o-mini") else model
        LlmProvider.QWEN -> return if (model.startsWith("GigaChat", ignoreCase = true)) {
            configuredModel("QWEN_MODEL", "qwen-flash")
        } else model
        LlmProvider.OPENAI, LlmProvider.ANTHROPIC -> Unit
        else -> return model
    }

    val normalized = model.trim()
    val known = LLMModel.entries.firstOrNull {
        it.alias.equals(normalized, ignoreCase = true) || it.name.equals(normalized, ignoreCase = true)
    }
    if (provider == LlmProvider.OPENAI && known == LLMModel.OpenAICompatibleCustom) {
        openaiModel?.trim()?.takeIf(String::isNotEmpty)?.let { return it }
    }
    val prefix = if (provider == LlmProvider.OPENAI) "gpt-" else "claude"
    if (normalized.startsWith(prefix, ignoreCase = true)) return normalized
    if (known?.provider == provider && known != LLMModel.OpenAICompatibleCustom) return known.alias

    val selected = gigaModel
    if (selected.provider == provider) return selected.alias
    val fallback = if (provider == LlmProvider.OPENAI) LLMModel.OpenAIGpt5Mini else LLMModel.AnthropicHaiku45
    return configuredModel("${provider.name}_MODEL", fallback.alias)
        .takeIf { it.startsWith(prefix, ignoreCase = true) } ?: fallback.alias
}

private fun configuredModel(key: String, fallback: String): String =
    System.getenv(key) ?: System.getProperty(key) ?: fallback
