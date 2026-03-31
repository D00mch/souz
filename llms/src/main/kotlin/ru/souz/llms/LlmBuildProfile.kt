package ru.souz.llms

private const val REGION_EN = "en"

class LlmBuildProfile(
    private val settingsProvider: LlmBuildProfileSettings,
    private val localProviderAvailability: LocalModelAvailability = LocalModelsUnavailable,
) {

    private fun currentEdition(): BuildEdition =
        if (settingsProvider.regionProfile == REGION_EN) BuildEdition.EN else BuildEdition.RU

    private fun currentDefaults(): Map<LlmProvider, LLMModel> =
        defaultsForEdition(currentEdition())

    val availableProviders: Set<LlmProvider>
        get() = currentDefaults().keys.filterTo(linkedSetOf()) { provider ->
            provider != LlmProvider.LOCAL || localProviderAvailability.isProviderAvailable()
        }

    val availableModels: List<LLMModel>
        get() = LLMModel.entries.filter(::isModelAvailable)

    val defaultModel: LLMModel
        get() = currentDefaults().values.first()

    val supportsSaluteSpeechRecognition: Boolean
        get() = currentEdition() == BuildEdition.RU

    fun normalizeModel(model: LLMModel): LLMModel = if (isModelAvailable(model)) model else defaultModel

    fun isModelAvailable(model: LLMModel): Boolean = when (model.provider) {
        LlmProvider.LOCAL -> model in localProviderAvailability.availableGigaModels()
        else -> model.provider in availableProviders
    }

    fun findModelByAlias(alias: String): LLMModel? = availableModels.firstOrNull { it.alias == alias }

    fun defaultModelForProvider(provider: LlmProvider): LLMModel? = when (provider) {
        LlmProvider.LOCAL -> localProviderAvailability.defaultGigaModel()
        else -> currentDefaults()[provider]
    }

    fun providerPriorities(): List<LlmProvider> = providerPrioritiesForEdition(currentEdition())

    companion object {
        private val providerDefaultsByEdition: Map<BuildEdition, Map<LlmProvider, LLMModel>> = mapOf(
            BuildEdition.RU to mapOf(
                LlmProvider.GIGA to LLMModel.Max,
                LlmProvider.QWEN to LLMModel.QwenMax,
                LlmProvider.AI_TUNNEL to LLMModel.AiTunnelClaudeHaiku,
                LlmProvider.LOCAL to LLMModel.LocalQwen3_4B_Instruct_2507,
            ),
            BuildEdition.EN to mapOf(
                LlmProvider.OPENAI to LLMModel.OpenAIGpt5Nano,
                LlmProvider.QWEN to LLMModel.QwenMax,
                LlmProvider.ANTHROPIC to LLMModel.AnthropicHaiku45,
                LlmProvider.LOCAL to LLMModel.LocalQwen3_4B_Instruct_2507,
            ),
        )

        fun defaultsForEdition(edition: BuildEdition): Map<LlmProvider, LLMModel> =
            providerDefaultsByEdition.getValue(edition)

        fun defaultsForLanguage(language: String): Map<LlmProvider, LLMModel> =
            defaultsForEdition(if (language.equals(REGION_EN, ignoreCase = true)) BuildEdition.EN else BuildEdition.RU)

        fun providerPrioritiesForEdition(edition: BuildEdition): List<LlmProvider> = when (edition) {
            BuildEdition.RU -> listOf(LlmProvider.AI_TUNNEL, LlmProvider.GIGA, LlmProvider.QWEN, LlmProvider.LOCAL)
            BuildEdition.EN -> listOf(LlmProvider.OPENAI, LlmProvider.ANTHROPIC, LlmProvider.QWEN, LlmProvider.LOCAL)
        }

        fun providerPrioritiesForLanguage(language: String): List<LlmProvider> =
            providerPrioritiesForEdition(
                if (language.equals(REGION_EN, ignoreCase = true)) BuildEdition.EN else BuildEdition.RU
            )
    }
}

private object LocalModelsUnavailable : LocalModelAvailability {
    override fun availableGigaModels(): List<LLMModel> = emptyList()

    override fun defaultGigaModel(): LLMModel? = null

    override fun isProviderAvailable(): Boolean = false
}
