package ru.souz.llms

private const val REGION_EN = "en"

class LlmBuildProfile(
    private val settingsProvider: LlmBuildProfileSettings,
    private val localProviderAvailability: LocalModelAvailability = LocalModelsUnavailable,
    private val combineRegionalCatalogs: Boolean = false,
) {

    private fun currentEdition(): BuildEdition =
        if (settingsProvider.regionProfile == REGION_EN) BuildEdition.EN else BuildEdition.RU

    private fun currentDefaults(): Map<LlmProvider, LLMModel> =
        if (combineRegionalCatalogs) {
            defaultsForEdition(currentEdition()) + desktopProviderDefaults
        } else {
            defaultsForEdition(currentEdition())
        }

    val availableProviders: Set<LlmProvider>
        get() = currentDefaults().keys.filterTo(linkedSetOf()) { provider ->
            provider != LlmProvider.LOCAL || localProviderAvailability.isProviderAvailable()
        }

    val availableModels: List<LLMModel>
        get() = LLMModel.entries.filter(::isModelAvailable)

    val defaultModel: LLMModel
        get() = currentDefaults().values.first()

    val supportsPolzaSpeechRecognition: Boolean
        get() = currentEdition() == BuildEdition.RU

    fun normalizeModel(model: LLMModel): LLMModel = if (isModelAvailable(model)) model else defaultModel

    fun isModelAvailable(model: LLMModel): Boolean {
        if (model == LLMModel.OpenAICompatibleCustom) return false
        return when (model.provider) {
            LlmProvider.LOCAL -> model in localProviderAvailability.availableGigaModels()
            else -> model.provider in availableProviders
        }
    }

    fun findModelByAlias(alias: String): LLMModel? = findLLMModel(alias)?.takeIf(::isModelAvailable)

    fun defaultModelForProvider(provider: LlmProvider): LLMModel? = when (provider) {
        LlmProvider.LOCAL -> localProviderAvailability.defaultGigaModel()
        else -> currentDefaults()[provider]
    }

    fun providerPriorities(): List<LlmProvider> {
        val preferred = providerPrioritiesForEdition(currentEdition())
        return if (combineRegionalCatalogs) {
            (preferred + BuildEdition.entries.flatMap(::providerPrioritiesForEdition))
                .distinct().filter { it != LlmProvider.LOCAL } + LlmProvider.LOCAL
        } else {
            preferred
        }
    }

    fun defaultConfiguredModel(hasKey: (LlmProvider) -> Boolean): LLMModel {
        val availableLocalDefault = localProviderAvailability.defaultGigaModel()
        return providerPriorities()
            .firstNotNullOfOrNull { provider ->
                when (provider) {
                    LlmProvider.LOCAL -> availableLocalDefault
                    else -> currentDefaults()[provider]?.takeIf { hasKey(it.provider) }
                }
            }
            ?: availableLocalDefault
            ?: defaultModel
    }

    fun normalizeConfiguredModel(
        model: LLMModel,
        customOpenAiModel: String?,
        fallback: () -> LLMModel,
    ): LLMModel = when {
        model == LLMModel.OpenAICompatibleCustom && customOpenAiModel.isNullOrBlank() -> fallback()
        model.provider == LlmProvider.LOCAL && model !in localProviderAvailability.availableGigaModels() -> fallback()
        model.provider !in currentDefaults() -> fallback()
        else -> model
    }

    fun defaultAmbientAnalysisModel(): LLMModel =
        localProviderAvailability.defaultGigaModel()
            ?: LLMModel.LocalQwen3_4B_Instruct_2507

    fun normalizeAmbientAnalysisModel(model: LLMModel): LLMModel = when {
        model.provider != LlmProvider.LOCAL -> defaultAmbientAnalysisModel()
        model !in localProviderAvailability.availableGigaModels() -> defaultAmbientAnalysisModel()
        else -> model
    }

    companion object {
        /** Desktop catalogs share providers; the persisted region only supplies legacy default priorities and speech policy. */
        fun desktop(
            settingsProvider: LlmBuildProfileSettings,
            localProviderAvailability: LocalModelAvailability = LocalModelsUnavailable,
        ): LlmBuildProfile = LlmBuildProfile(settingsProvider, localProviderAvailability, combineRegionalCatalogs = true)

        private val providerDefaultsByEdition: Map<BuildEdition, Map<LlmProvider, LLMModel>> = mapOf(
            BuildEdition.RU to mapOf(
                LlmProvider.GIGA to LLMModel.Max,
                LlmProvider.QWEN to LLMModel.QwenMax,
                LlmProvider.AI_TUNNEL to LLMModel.AiTunnelClaudeHaiku,
                LlmProvider.LOCAL to LLMModel.LocalQwen3_4B_Instruct_2507,
            ),
            BuildEdition.EN to mapOf(
                LlmProvider.CODEX to LLMModel.CodexGpt54,
                LlmProvider.OPENAI to LLMModel.OpenAIGpt5Mini,
                LlmProvider.QWEN to LLMModel.QwenMax,
                LlmProvider.ANTHROPIC to LLMModel.AnthropicHaiku45,
                LlmProvider.LOCAL to LLMModel.LocalQwen3_4B_Instruct_2507,
            ),
        )

        private val desktopProviderDefaults =
            providerDefaultsByEdition.getValue(BuildEdition.RU) + providerDefaultsByEdition.getValue(BuildEdition.EN)

        fun defaultsForEdition(edition: BuildEdition): Map<LlmProvider, LLMModel> =
            providerDefaultsByEdition.getValue(edition)

        fun defaultsForLanguage(language: String): Map<LlmProvider, LLMModel> =
            defaultsForEdition(if (language.equals(REGION_EN, ignoreCase = true)) BuildEdition.EN else BuildEdition.RU)

        fun providerPrioritiesForEdition(edition: BuildEdition): List<LlmProvider> = when (edition) {
            BuildEdition.RU -> listOf(LlmProvider.AI_TUNNEL, LlmProvider.GIGA, LlmProvider.QWEN, LlmProvider.LOCAL)
            BuildEdition.EN -> listOf(LlmProvider.CODEX, LlmProvider.OPENAI, LlmProvider.ANTHROPIC, LlmProvider.QWEN, LlmProvider.LOCAL)
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
