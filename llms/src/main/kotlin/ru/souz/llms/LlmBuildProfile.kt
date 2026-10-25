package ru.souz.llms

private const val REGION_EN = "en"

class LlmBuildProfile(
    private val settingsProvider: LlmBuildProfileSettings,
) {

    private fun currentEdition(): BuildEdition =
        if (settingsProvider.regionProfile == REGION_EN) BuildEdition.EN else BuildEdition.RU

    private fun currentDefaults(): Map<LlmProvider, LLMModel> =
        defaultsForEdition(currentEdition())

    val availableProviders: Set<LlmProvider>
        get() = currentDefaults().keys

    val availableModels: List<LLMModel>
        get() = LLMModel.entries.filter(::isModelAvailable)

    val defaultModel: LLMModel
        get() = currentDefaults().values.first()

    fun normalizeModel(model: LLMModel): LLMModel = if (isModelAvailable(model)) model else defaultModel

    fun isModelAvailable(model: LLMModel): Boolean {
        if (model == LLMModel.OpenAICompatibleCustom) return false
        return model.provider in availableProviders
    }

    fun findModelByAlias(alias: String): LLMModel? = findLLMModel(alias)?.takeIf(::isModelAvailable)

    fun defaultModelForProvider(provider: LlmProvider): LLMModel? = currentDefaults()[provider]

    fun providerPriorities(): List<LlmProvider> = providerPrioritiesForEdition(currentEdition())

    fun defaultConfiguredModel(hasKey: (LlmProvider) -> Boolean): LLMModel =
        providerPriorities().firstNotNullOfOrNull { provider ->
            currentDefaults()[provider]?.takeIf { hasKey(it.provider) }
        } ?: defaultModel

    fun normalizeConfiguredModel(
        model: LLMModel,
        customOpenAiModel: String?,
        fallback: () -> LLMModel,
    ): LLMModel = when {
        model == LLMModel.OpenAICompatibleCustom && customOpenAiModel.isNullOrBlank() -> fallback()
        model.provider !in currentDefaults() -> fallback()
        else -> model
    }

    companion object {
        private val providerDefaultsByEdition: Map<BuildEdition, Map<LlmProvider, LLMModel>> = mapOf(
            BuildEdition.RU to mapOf(
                LlmProvider.GIGA to LLMModel.Max,
                LlmProvider.QWEN to LLMModel.QwenMax,
                LlmProvider.AI_TUNNEL to LLMModel.AiTunnelClaudeHaiku,
            ),
            BuildEdition.EN to mapOf(
                LlmProvider.CODEX to LLMModel.CodexGpt54,
                LlmProvider.OPENAI to LLMModel.OpenAIGpt5Mini,
                LlmProvider.QWEN to LLMModel.QwenMax,
                LlmProvider.ANTHROPIC to LLMModel.AnthropicHaiku45,
            ),
        )

        fun defaultsForEdition(edition: BuildEdition): Map<LlmProvider, LLMModel> =
            providerDefaultsByEdition.getValue(edition)

        fun defaultsForLanguage(language: String): Map<LlmProvider, LLMModel> =
            defaultsForEdition(if (language.equals(REGION_EN, ignoreCase = true)) BuildEdition.EN else BuildEdition.RU)

        fun providerPrioritiesForEdition(edition: BuildEdition): List<LlmProvider> = when (edition) {
            BuildEdition.RU -> listOf(LlmProvider.AI_TUNNEL, LlmProvider.GIGA, LlmProvider.QWEN)
            BuildEdition.EN -> listOf(LlmProvider.CODEX, LlmProvider.OPENAI, LlmProvider.ANTHROPIC, LlmProvider.QWEN)
        }

        fun providerPrioritiesForLanguage(language: String): List<LlmProvider> =
            providerPrioritiesForEdition(
                if (language.equals(REGION_EN, ignoreCase = true)) BuildEdition.EN else BuildEdition.RU
            )
    }
}
