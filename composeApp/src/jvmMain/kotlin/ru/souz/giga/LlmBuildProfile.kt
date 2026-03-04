package ru.souz.giga

import ru.souz.db.SettingsProvider
import ru.souz.llms.BuildEdition
import ru.souz.db.SettingsProviderImpl.Companion.REGION_EN

class LlmBuildProfile(
    private val settingsProvider: SettingsProvider,
) {

    private fun currentEdition(): BuildEdition =
        if (settingsProvider.regionProfile == REGION_EN) BuildEdition.EN else BuildEdition.RU

    private fun currentDefaults(): Map<LlmProvider, GigaModel> =
        defaultsForEdition(currentEdition())

    val availableProviders: Set<LlmProvider>
        get() = currentDefaults().keys

    val availableModels: List<GigaModel>
        get() = GigaModel.entries.filter { it.provider in availableProviders }

    val defaultModel: GigaModel
        get() = currentDefaults().values.first()

    val supportsSaluteSpeechRecognition: Boolean
        get() = currentEdition() == BuildEdition.RU

    fun normalizeModel(model: GigaModel): GigaModel = if (isModelAvailable(model)) model else defaultModel

    fun isModelAvailable(model: GigaModel): Boolean = model.provider in availableProviders

    fun findModelByAlias(alias: String): GigaModel? = availableModels.firstOrNull { it.alias == alias }

    fun defaultModelForProvider(provider: LlmProvider): GigaModel? = currentDefaults()[provider]

    fun providerPriorities(): List<LlmProvider> = when (currentEdition()) {
        BuildEdition.RU -> listOf(LlmProvider.AI_TUNNEL, LlmProvider.GIGA, LlmProvider.QWEN)
        BuildEdition.EN -> listOf(LlmProvider.OPENAI, LlmProvider.ANTHROPIC, LlmProvider.QWEN)
    }

    companion object {
        private val providerDefaultsByEdition: Map<BuildEdition, Map<LlmProvider, GigaModel>> = mapOf(
            BuildEdition.RU to mapOf(
                LlmProvider.GIGA to GigaModel.Max,
                LlmProvider.QWEN to GigaModel.QwenMax,
                LlmProvider.AI_TUNNEL to GigaModel.AiTunnelClaudeHaiku,
            ),
            BuildEdition.EN to mapOf(
                LlmProvider.OPENAI to GigaModel.OpenAIGpt5Nano,
                LlmProvider.QWEN to GigaModel.QwenMax,
                LlmProvider.ANTHROPIC to GigaModel.AnthropicHaiku45,
            ),
        )

        fun defaultsForEdition(edition: BuildEdition): Map<LlmProvider, GigaModel> =
            providerDefaultsByEdition.getValue(edition)

        fun defaultsForLanguage(language: String): Map<LlmProvider, GigaModel> =
            defaultsForEdition(if (language.equals(REGION_EN, ignoreCase = true)) BuildEdition.EN else BuildEdition.RU)
    }
}
