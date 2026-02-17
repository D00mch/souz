package ru.gigadesk.ui.settings

import ru.gigadesk.db.SettingsProvider
import ru.gigadesk.db.hasKey
import ru.gigadesk.giga.EmbeddingsModel
import ru.gigadesk.giga.EmbeddingsProvider
import ru.gigadesk.giga.GigaModel
import ru.gigadesk.giga.LlmBuildProfile
import ru.gigadesk.giga.LlmProvider

fun SettingsProvider.availableLlmModels(): List<GigaModel> =
    LlmBuildProfile.availableModels.filter { model -> this.hasKey(model.provider) }

fun SettingsProvider.defaultLlmModel(): GigaModel? {
    val availableModels = this.availableLlmModels()
    if (availableModels.isEmpty()) return null

    val preferredProvider = LlmBuildProfile.setupProviderPriority()
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

    val preferredProvider = LlmBuildProfile.setupProviderPriority()
        .mapNotNull { it.toEmbeddingsProviderOrNull() }
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
