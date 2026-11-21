package ru.souz.db

import ru.souz.llms.LLMModel
import ru.souz.llms.LlmProvider
import ru.souz.llms.LocalModelAvailability

class DesktopModelCatalog(private val localModels: LocalModelAvailability? = null) {
    val supportsLocalInference: Boolean
        get() = localModels?.isProviderAvailable() == true

    fun availableModels(customOpenAiModel: String?): List<LLMModel> {
        val supportedLocalModels = localModels?.availableGigaModels().orEmpty()
        val builtInModels = LLMModel.entries.filter {
            it != LLMModel.OpenAICompatibleCustom && (it.provider != LlmProvider.LOCAL || it in supportedLocalModels)
        }
        return if (customOpenAiModel.isNullOrBlank()) builtInModels else builtInModels + LLMModel.OpenAICompatibleCustom
    }

    fun supportsModel(model: LLMModel, customOpenAiModel: String?): Boolean = when {
        model == LLMModel.OpenAICompatibleCustom -> !customOpenAiModel.isNullOrBlank()
        model.provider == LlmProvider.LOCAL -> isLocalModelAvailable(model)
        else -> true
    }

    fun defaultChatModel(customOpenAiModel: String?, hasKey: (LlmProvider) -> Boolean): LLMModel? =
        automaticCloudModels.firstOrNull { supportsModel(it, customOpenAiModel) && hasKey(it.provider) }
            ?: defaultLocalModel()?.takeIf { hasKey(LlmProvider.LOCAL) }

    fun isLocalModelAvailable(model: LLMModel): Boolean = model in localModels?.availableGigaModels().orEmpty()

    fun defaultLocalModel(): LLMModel? = localModels?.defaultGigaModel()

    companion object {
        val fallbackChatModel: LLMModel = LLMModel.QwenMax

        private val automaticCloudModels = listOf(
            LLMModel.OpenAICompatibleCustom,
            LLMModel.CodexGpt54,
            LLMModel.OpenAIGpt5Mini,
            LLMModel.AnthropicHaiku45,
            LLMModel.AiTunnelClaudeHaiku,
            LLMModel.Max,
            fallbackChatModel,
        )
    }
}
