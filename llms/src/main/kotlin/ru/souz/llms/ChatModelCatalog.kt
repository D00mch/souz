package ru.souz.llms

private val automaticCloudModels = listOf(
    LLMModel.OpenAICompatibleCustom,
    LLMModel.CodexGpt54,
    LLMModel.OpenAIGpt5Mini,
    LLMModel.AnthropicHaiku45,
    LLMModel.AiTunnelClaudeHaiku,
    LLMModel.Max,
    LLMModel.QwenMax,
)

fun LocalModelAvailability.automaticChatModels(customModel: String?): Sequence<LLMModel> = sequence {
    yieldAll(automaticCloudModels.filter { it != LLMModel.OpenAICompatibleCustom || !customModel.isNullOrBlank() })
    defaultGigaModel()?.takeIf { it in availableGigaModels() }?.let { yield(it) }
}

fun LocalModelAvailability.supportedChatModels(customModel: String?): List<LLMModel> {
    val localModels = availableGigaModels()
    return LLMModel.entries.filter { it.isSupportedChatModel(customModel) { localModels } }
}

inline fun LLMModel.isSupportedChatModel(customModel: String?, localModels: () -> List<LLMModel>): Boolean =
    when {
        this == LLMModel.OpenAICompatibleCustom -> !customModel.isNullOrBlank()
        provider == LlmProvider.LOCAL -> this in localModels()
        else -> true
    }
