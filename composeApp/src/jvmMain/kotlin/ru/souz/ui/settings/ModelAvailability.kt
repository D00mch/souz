package ru.souz.ui.settings

import ru.souz.db.SettingsProvider
import ru.souz.db.hasKey
import ru.souz.llms.EmbeddingsModel
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmBuildProfile
import ru.souz.llms.LlmProvider
import ru.souz.llms.VoiceRecognitionModel
import ru.souz.llms.VoiceRecognitionProvider
import ru.souz.llms.local.LocalEmbeddingProfiles
import ru.souz.ui.main.usecases.MacOsSpeechBridgeLoader

fun SettingsProvider.availableLlmModels(llmBuildProfile: LlmBuildProfile): List<LLMModel> =
    llmBuildProfile.availableModels.filter { model -> this.hasKey(model.provider) }

fun SettingsProvider.defaultLlmModel(llmBuildProfile: LlmBuildProfile): LLMModel? {
    val availableModels = this.availableLlmModels(llmBuildProfile)
    if (availableModels.isEmpty()) return null

    val preferredProvider = llmBuildProfile.providerPriorities()
        .firstOrNull(this::hasKey)

    return preferredProvider
        ?.let(llmBuildProfile::defaultModelForProvider)
        ?.takeIf { model -> model in availableModels }
        ?: availableModels.first()
}

fun SettingsProvider.availableEmbeddingsModels(llmBuildProfile: LlmBuildProfile): List<EmbeddingsModel> =
    when {
        gigaModel.provider == LlmProvider.LOCAL && LlmProvider.LOCAL in llmBuildProfile.availableProviders ->
            listOf(LocalEmbeddingProfiles.default().embeddingsModel)

        else -> EmbeddingsModel.entries.filter { model ->
            model.provider != LlmProvider.LOCAL &&
                this.hasKey(model.provider) &&
                model.provider in llmBuildProfile.availableProviders
        }
    }

fun SettingsProvider.defaultEmbeddingsModel(llmBuildProfile: LlmBuildProfile): EmbeddingsModel? {
    if (gigaModel.provider == LlmProvider.LOCAL && LlmProvider.LOCAL in llmBuildProfile.availableProviders) {
        return LocalEmbeddingProfiles.default().embeddingsModel
    }
    val availableModels = this.availableEmbeddingsModels(llmBuildProfile)
    if (availableModels.isEmpty()) return null

    val preferredProvider = llmBuildProfile.providerPriorities()
        .firstOrNull { provider -> availableModels.any { it.provider == provider } }

    return preferredProvider
        ?.let { provider -> availableModels.firstOrNull { it.provider == provider } }
        ?: availableModels.first()
}

fun SettingsProvider.availableVoiceRecognitionModels(llmBuildProfile: LlmBuildProfile): List<VoiceRecognitionModel> =
    VoiceRecognitionModel.entries.filter { model ->
        model.provider.isEnabledInCurrentEnvironment(llmBuildProfile) &&
            (model.provider == VoiceRecognitionProvider.LOCAL_MACOS || this.hasKey(model.provider))
    }

fun SettingsProvider.defaultVoiceRecognitionModel(llmBuildProfile: LlmBuildProfile): VoiceRecognitionModel? {
    val availableModels = this.availableVoiceRecognitionModels(llmBuildProfile)
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
}

private fun VoiceRecognitionProvider.isEnabledInBuild(llmBuildProfile: LlmBuildProfile): Boolean = when (this) {
    VoiceRecognitionProvider.SALUTE_SPEECH -> llmBuildProfile.supportsSaluteSpeechRecognition
    VoiceRecognitionProvider.AI_TUNNEL -> LlmProvider.AI_TUNNEL in llmBuildProfile.availableProviders
    VoiceRecognitionProvider.OPENAI -> LlmProvider.OPENAI in llmBuildProfile.availableProviders
    VoiceRecognitionProvider.LOCAL_MACOS -> false
}

private fun VoiceRecognitionProvider.isEnabledInCurrentEnvironment(llmBuildProfile: LlmBuildProfile): Boolean = when (this) {
    VoiceRecognitionProvider.LOCAL_MACOS -> MacOsSpeechBridgeLoader.isCurrentHost()
    else -> isEnabledInBuild(llmBuildProfile)
}
