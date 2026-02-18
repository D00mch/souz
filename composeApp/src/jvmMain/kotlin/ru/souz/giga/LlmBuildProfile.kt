package ru.souz.giga

import ru.souz.edition.BuildEdition
import ru.souz.edition.BuildEditionConfig

object LlmBuildProfile {

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

    val availableProviders: Set<LlmProvider> = providerDefaultsByEdition
        .getValue(BuildEditionConfig.current)
        .keys

    val availableModels: List<GigaModel> = GigaModel.entries.filter { it.provider in availableProviders }

    val defaultModel: GigaModel = providerDefaultsByEdition
        .getValue(BuildEditionConfig.current)
        .values
        .first()

    val supportsSaluteSpeechRecognition: Boolean = BuildEditionConfig.current == BuildEdition.RU

    fun normalizeModel(model: GigaModel): GigaModel = if (isModelAvailable(model)) model else defaultModel

    fun isModelAvailable(model: GigaModel): Boolean = model.provider in availableProviders

    fun findModelByAlias(alias: String): GigaModel? = availableModels.firstOrNull { it.alias == alias }

    fun defaultModelForProvider(provider: LlmProvider): GigaModel? = providerDefaultsByEdition
        .getValue(BuildEditionConfig.current)[provider]

    fun providerPriorities(): List<LlmProvider> = when (BuildEditionConfig.current) {
        BuildEdition.RU -> listOf(LlmProvider.AI_TUNNEL, LlmProvider.GIGA, LlmProvider.QWEN)
        BuildEdition.EN -> listOf(LlmProvider.OPENAI, LlmProvider.ANTHROPIC, LlmProvider.QWEN)
    }
}
