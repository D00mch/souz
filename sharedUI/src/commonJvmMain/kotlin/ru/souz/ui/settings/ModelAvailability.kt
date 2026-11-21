package ru.souz.ui.settings

import ru.souz.db.DesktopModelCatalog
import ru.souz.db.REGION_EN
import ru.souz.db.SettingsProvider
import ru.souz.llms.EmbeddingsModel
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmProvider
import ru.souz.llms.VoiceRecognitionModel
import ru.souz.llms.VoiceRecognitionProvider

fun SettingsProvider.availableLlmModels(modelCatalog: DesktopModelCatalog): List<LLMModel> =
    modelCatalog.availableModels(openaiModel).filter { hasKey(it.provider) }

fun SettingsProvider.defaultLlmModel(modelCatalog: DesktopModelCatalog): LLMModel? =
    modelCatalog.defaultChatModel(openaiModel, ::hasKey) ?: availableLlmModels(modelCatalog).firstOrNull()

fun SettingsProvider.availableAmbientAnalysisModels(modelCatalog: DesktopModelCatalog): List<LLMModel> =
    modelCatalog.availableModels(null).filter { it.provider == LlmProvider.LOCAL }

fun SettingsProvider.defaultAmbientAnalysisModel(modelCatalog: DesktopModelCatalog): LLMModel? {
    val availableModels = availableAmbientAnalysisModels(modelCatalog)
    return modelCatalog.defaultLocalModel()?.takeIf { it in availableModels } ?: availableModels.firstOrNull()
}

fun SettingsProvider.availableEmbeddingsModels(modelCatalog: DesktopModelCatalog): List<EmbeddingsModel> =
    if (gigaModel.provider == LlmProvider.LOCAL && modelCatalog.supportsLocalInference) {
        listOf(EmbeddingsModel.LocalEmbeddingGemma300M)
    } else {
        EmbeddingsModel.entries.filter { it.provider != LlmProvider.LOCAL && hasKey(it.provider) }
    }

fun SettingsProvider.defaultEmbeddingsModel(modelCatalog: DesktopModelCatalog): EmbeddingsModel? {
    val models = availableEmbeddingsModels(modelCatalog)
    val priorities = if (regionProfile == REGION_EN) englishEmbeddingPriorities else russianEmbeddingPriorities
    return priorities.firstNotNullOfOrNull { provider -> models.firstOrNull { it.provider == provider } }
        ?: models.firstOrNull()
}

private val englishEmbeddingPriorities = listOf(LlmProvider.OPENAI, LlmProvider.QWEN, LlmProvider.AI_TUNNEL, LlmProvider.GIGA)
private val russianEmbeddingPriorities = listOf(LlmProvider.AI_TUNNEL, LlmProvider.GIGA, LlmProvider.QWEN, LlmProvider.OPENAI)

fun SettingsProvider.availableVoiceRecognitionModels(
    localMacOsSpeechAvailable: Boolean = false,
): List<VoiceRecognitionModel> {
    val useEnglishSpeech = regionProfile == REGION_EN
    return VoiceRecognitionModel.entries.filter { model ->
        when (model.provider) {
            VoiceRecognitionProvider.POLZA, VoiceRecognitionProvider.AI_TUNNEL -> !useEnglishSpeech && hasKey(model.provider)
            VoiceRecognitionProvider.OPENAI -> useEnglishSpeech && hasKey(model.provider)
            VoiceRecognitionProvider.LOCAL_MACOS -> localMacOsSpeechAvailable
        }
    }
}

fun SettingsProvider.defaultVoiceRecognitionModel(
    localMacOsSpeechAvailable: Boolean = false,
): VoiceRecognitionModel? {
    val models = availableVoiceRecognitionModels(localMacOsSpeechAvailable)
    return models.firstOrNull { it.provider == VoiceRecognitionProvider.AI_TUNNEL } ?: models.firstOrNull()
}
