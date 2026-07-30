package ru.souz.ui.settings

import ru.souz.db.SettingsProvider
import ru.souz.llms.EmbeddingsModel
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmBuildProfile
import ru.souz.llms.LlmProvider
import ru.souz.llms.VoiceRecognitionModel
import ru.souz.llms.VoiceRecognitionProvider

fun SettingsProvider.availableLlmModels(llmBuildProfile: LlmBuildProfile): List<LLMModel> =
    llmBuildProfile.availableModels.filter { model -> this.hasKey(model.provider) }

fun SettingsProvider.defaultLlmModel(llmBuildProfile: LlmBuildProfile): LLMModel? {
    val availableModels = this.availableLlmModels(llmBuildProfile)
    if (availableModels.isEmpty()) return null

    val availableProviders = availableModels.mapTo(linkedSetOf()) { it.provider }
    val preferredProvider = llmBuildProfile.providerPriorities()
        .firstOrNull { it in availableProviders }

    return preferredProvider
        ?.let(llmBuildProfile::defaultModelForProvider)
        ?.takeIf { model -> model in availableModels }
        ?: availableModels.first()
}

fun SettingsProvider.availableAmbientAnalysisModels(llmBuildProfile: LlmBuildProfile): List<LLMModel> =
    llmBuildProfile.availableModels.filter { model -> model.provider == LlmProvider.LOCAL }

fun SettingsProvider.defaultAmbientAnalysisModel(llmBuildProfile: LlmBuildProfile): LLMModel? {
    val availableModels = this.availableAmbientAnalysisModels(llmBuildProfile)
    if (availableModels.isEmpty()) return null

    return llmBuildProfile.defaultModelForProvider(LlmProvider.LOCAL)
        ?.takeIf { model -> model in availableModels }
        ?: availableModels.first()
}

fun SettingsProvider.availableEmbeddingsModels(llmBuildProfile: LlmBuildProfile): List<EmbeddingsModel> =
    when {
        gigaModel.provider == LlmProvider.LOCAL && LlmProvider.LOCAL in llmBuildProfile.availableProviders ->
            listOf(EmbeddingsModel.LocalEmbeddingGemma300M)

        else -> EmbeddingsModel.entries.filter { model ->
            model.provider != LlmProvider.LOCAL &&
                this.hasKey(model.provider) &&
                model.provider in llmBuildProfile.availableProviders
        }
    }

fun SettingsProvider.defaultEmbeddingsModel(llmBuildProfile: LlmBuildProfile): EmbeddingsModel? {
    if (gigaModel.provider == LlmProvider.LOCAL && LlmProvider.LOCAL in llmBuildProfile.availableProviders) {
        return EmbeddingsModel.LocalEmbeddingGemma300M
    }
    val availableModels = this.availableEmbeddingsModels(llmBuildProfile)
    if (availableModels.isEmpty()) return null

    val preferredProvider = llmBuildProfile.providerPriorities()
        .firstOrNull { provider -> availableModels.any { it.provider == provider } }

    return preferredProvider
        ?.let { provider -> availableModels.firstOrNull { it.provider == provider } }
        ?: availableModels.first()
}

fun SettingsProvider.availableVoiceRecognitionModels(
    llmBuildProfile: LlmBuildProfile,
    localMacOsSpeechAvailable: Boolean = false,
): List<VoiceRecognitionModel> =
    VoiceRecognitionModel.entries.filter { model ->
        model.provider.isEnabledInCurrentEnvironment(llmBuildProfile, localMacOsSpeechAvailable) &&
            (model.provider == VoiceRecognitionProvider.LOCAL_MACOS || this.hasKey(model.provider))
    }

fun SettingsProvider.defaultVoiceRecognitionModel(
    llmBuildProfile: LlmBuildProfile,
    localMacOsSpeechAvailable: Boolean = false,
): VoiceRecognitionModel? {
    val availableModels = this.availableVoiceRecognitionModels(llmBuildProfile, localMacOsSpeechAvailable)
    if (availableModels.isEmpty()) return null

    val preferredProvider = llmBuildProfile.providerPriorities()
        .mapNotNull { it.toVoiceRecognitionProviderOrNull() }
        .firstOrNull { provider -> availableModels.any { it.provider == provider } }

    return preferredProvider
        ?.let { provider -> availableModels.firstOrNull { it.provider == provider } }
        ?: availableModels.first()
}

private fun LlmProvider.toVoiceRecognitionProviderOrNull(): VoiceRecognitionProvider? = when (this) {
    LlmProvider.GIGA -> VoiceRecognitionProvider.SALUTE_SPEECH
    LlmProvider.AI_TUNNEL -> VoiceRecognitionProvider.AI_TUNNEL
    LlmProvider.OPENAI -> VoiceRecognitionProvider.OPENAI
    LlmProvider.QWEN -> VoiceRecognitionProvider.OPENAI
    LlmProvider.ANTHROPIC -> VoiceRecognitionProvider.OPENAI
    LlmProvider.LOCAL -> null
    LlmProvider.CODEX -> null
}

private fun VoiceRecognitionProvider.isEnabledInBuild(llmBuildProfile: LlmBuildProfile): Boolean = when (this) {
    VoiceRecognitionProvider.SALUTE_SPEECH -> llmBuildProfile.supportsSaluteSpeechRecognition
    VoiceRecognitionProvider.AI_TUNNEL -> LlmProvider.AI_TUNNEL in llmBuildProfile.availableProviders
    VoiceRecognitionProvider.OPENAI -> LlmProvider.OPENAI in llmBuildProfile.availableProviders
    VoiceRecognitionProvider.LOCAL_MACOS -> false
}

private fun VoiceRecognitionProvider.isEnabledInCurrentEnvironment(
    llmBuildProfile: LlmBuildProfile,
    localMacOsSpeechAvailable: Boolean,
): Boolean = when (this) {
    VoiceRecognitionProvider.LOCAL_MACOS -> localMacOsSpeechAvailable
    else -> isEnabledInBuild(llmBuildProfile)
}
