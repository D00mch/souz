package ru.souz.ui.settings

import ru.souz.db.SettingsProvider
import ru.souz.db.hasKey
import ru.souz.edition.BuildEdition
import ru.souz.edition.BuildEditionConfig
import ru.souz.giga.EmbeddingsModel
import ru.souz.giga.EmbeddingsProvider
import ru.souz.giga.GigaModel
import ru.souz.giga.LlmBuildProfile
import ru.souz.giga.LlmProvider
import ru.souz.giga.VoiceRecognitionModel
import ru.souz.giga.VoiceRecognitionProvider

fun SettingsProvider.availableLlmModels(): List<GigaModel> =
    LlmBuildProfile.availableModels.filter { model -> this.hasKey(model.provider) }

fun SettingsProvider.defaultLlmModel(): GigaModel? {
    val availableModels = this.availableLlmModels()
    if (availableModels.isEmpty()) return null

    val preferredProvider = LlmBuildProfile.providerPriorities()
        .firstOrNull(this::hasKey)

    return preferredProvider
        ?.let(LlmBuildProfile::defaultModelForProvider)
        ?.takeIf { model -> model in availableModels }
        ?: availableModels.first()
}

fun SettingsProvider.availableEmbeddingsModels(): List<EmbeddingsModel> = EmbeddingsModel.entries
    .filter { model -> this.hasKey(model.provider) }

fun SettingsProvider.defaultEmbeddingsModel(): EmbeddingsModel? {
    val availableModels = this.availableEmbeddingsModels()
    if (availableModels.isEmpty()) return null

    val preferredProvider = LlmBuildProfile.providerPriorities()
        .mapNotNull { it.toEmbeddingsProviderOrNull() }
        .firstOrNull { provider -> availableModels.any { it.provider == provider } }

    return preferredProvider
        ?.let { provider -> availableModels.firstOrNull { it.provider == provider } }
        ?: availableModels.first()
}

fun SettingsProvider.availableVoiceRecognitionModels(): List<VoiceRecognitionModel> = VoiceRecognitionModel.entries
    .filter { model -> model.provider.isEnabledInBuild() && this.hasKey(model.provider) }

fun SettingsProvider.defaultVoiceRecognitionModel(): VoiceRecognitionModel? {
    val availableModels = this.availableVoiceRecognitionModels()
    if (availableModels.isEmpty()) return null

    val preferredProvider = LlmBuildProfile.providerPriorities()
        .mapNotNull { it.toVoiceRecognitionProviderOrNull() }
        .firstOrNull { provider -> availableModels.any { it.provider == provider } }

    return preferredProvider
        ?.let { provider -> availableModels.firstOrNull { it.provider == provider } }
        ?: availableModels.first()
}

/** Some llms doesn't have embeddings, Anthropic, for example */
private fun LlmProvider.toEmbeddingsProviderOrNull(): EmbeddingsProvider? = when (this) {
    LlmProvider.GIGA -> EmbeddingsProvider.GIGA
    LlmProvider.QWEN -> EmbeddingsProvider.QWEN
    LlmProvider.AI_TUNNEL -> EmbeddingsProvider.AI_TUNNEL
    LlmProvider.ANTHROPIC -> null
    LlmProvider.OPENAI -> EmbeddingsProvider.OPENAI
}

private fun LlmProvider.toVoiceRecognitionProviderOrNull(): VoiceRecognitionProvider? = when (this) {
    LlmProvider.GIGA -> VoiceRecognitionProvider.SALUTE_SPEECH
    LlmProvider.AI_TUNNEL -> VoiceRecognitionProvider.AI_TUNNEL
    LlmProvider.OPENAI -> VoiceRecognitionProvider.OPENAI
    LlmProvider.QWEN -> VoiceRecognitionProvider.OPENAI
    LlmProvider.ANTHROPIC -> VoiceRecognitionProvider.OPENAI
}

private fun VoiceRecognitionProvider.isEnabledInBuild(): Boolean = when (this) {
    VoiceRecognitionProvider.SALUTE_SPEECH -> LlmBuildProfile.supportsSaluteSpeechRecognition
    VoiceRecognitionProvider.AI_TUNNEL -> BuildEditionConfig.current == BuildEdition.RU
    VoiceRecognitionProvider.OPENAI -> BuildEditionConfig.current == BuildEdition.EN
}
