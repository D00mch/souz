package ru.souz.ui.settings

import ru.souz.db.REGION_EN
import ru.souz.db.SettingsProvider
import ru.souz.llms.EmbeddingsModel
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmProvider
import ru.souz.llms.LocalModelAvailability
import ru.souz.llms.VoiceRecognitionModel
import ru.souz.llms.VoiceRecognitionProvider
import ru.souz.llms.supportedChatModels

fun SettingsProvider.availableLlmModels(localModels: LocalModelAvailability): List<LLMModel> =
    localModels.supportedChatModels(openaiModel).filter { hasKey(it.provider) }

private val englishEmbeddingProviders = listOf(LlmProvider.OPENAI, LlmProvider.QWEN)
private val russianEmbeddingProviders = listOf(LlmProvider.AI_TUNNEL, LlmProvider.GIGA, LlmProvider.QWEN)

private fun SettingsProvider.embeddingProviders(): List<LlmProvider> =
    if (regionProfile == REGION_EN) englishEmbeddingProviders else russianEmbeddingProviders

fun SettingsProvider.availableEmbeddingsModels(localModels: LocalModelAvailability): List<EmbeddingsModel> {
    if (gigaModel.provider == LlmProvider.LOCAL && localModels.isProviderAvailable()) {
        return listOf(EmbeddingsModel.LocalEmbeddingGemma300M)
    }
    val providers = embeddingProviders()
    return EmbeddingsModel.entries.filter { it.provider in providers && hasKey(it.provider) }
}

fun SettingsProvider.defaultEmbeddingsModel(available: List<EmbeddingsModel>): EmbeddingsModel? =
    embeddingProviders().firstNotNullOfOrNull { provider -> available.firstOrNull { it.provider == provider } }
        ?: available.firstOrNull()

fun SettingsProvider.availableVoiceRecognitionModels(
    localMacOsSpeechAvailable: Boolean = false,
): List<VoiceRecognitionModel> =
    VoiceRecognitionModel.entries.filter { model ->
        when (model.provider) {
            VoiceRecognitionProvider.POLZA, VoiceRecognitionProvider.AI_TUNNEL ->
                regionProfile != REGION_EN && hasKey(model.provider)
            VoiceRecognitionProvider.OPENAI -> regionProfile == REGION_EN && hasKey(model.provider)
            VoiceRecognitionProvider.LOCAL_MACOS -> localMacOsSpeechAvailable
        }
    }

fun defaultVoiceRecognitionModel(available: List<VoiceRecognitionModel>): VoiceRecognitionModel? =
    available.firstOrNull { it.provider == VoiceRecognitionProvider.AI_TUNNEL } ?: available.firstOrNull()
