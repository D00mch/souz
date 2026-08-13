package ru.souz.llms

const val DEFAULT_EMBEDDINGS_MODEL: String = "Embeddings"

sealed interface ModelResolution<out T> {
    data class Resolved<T>(val value: T) : ModelResolution<T>

    data class Unknown(val normalizedInput: String) : ModelResolution<Nothing>

    data class UnsupportedProvider<T>(
        val value: T,
        val provider: LlmProvider,
    ) : ModelResolution<T>

    data class Ambiguous<T>(
        val normalizedInput: String,
        val candidates: List<T>,
    ) : ModelResolution<T>
}

sealed interface EmbeddingsModelSelection {
    data object Default : EmbeddingsModelSelection

    data class Explicit(val model: EmbeddingsModel) : EmbeddingsModelSelection
}

fun resolveChatModel(
    rawModel: String,
    supportedProviders: Set<LlmProvider> = LlmProvider.entries.toSet(),
    preferredModel: LLMModel? = null,
): ModelResolution<LLMModel> {
    val normalized = rawModel.trim()
    if (normalized.isEmpty()) return ModelResolution.Unknown(normalized)

    val exactName = LLMModel.entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) }
    if (exactName != null) return exactName.withProviderSupport(supportedProviders)

    val candidates = LLMModel.entries.filter { model ->
        model.alias.equals(normalized, ignoreCase = true) ||
            model.legacyAliases.any { it.equals(normalized, ignoreCase = true) }
    }
    val selected = when {
        candidates.size == 1 -> candidates.single()
        preferredModel != null && preferredModel in candidates -> preferredModel
        candidates.isEmpty() -> return ModelResolution.Unknown(normalized)
        else -> return ModelResolution.Ambiguous(normalized, candidates)
    }
    return selected.withProviderSupport(supportedProviders)
}

fun resolveEmbeddingsModel(
    rawModel: String,
    configuredModel: EmbeddingsModel? = null,
    supportedProviders: Set<LlmProvider> = LlmProvider.entries.toSet(),
): ModelResolution<EmbeddingsModelSelection> {
    val normalized = rawModel.trim()
    if (normalized.equals(DEFAULT_EMBEDDINGS_MODEL, ignoreCase = true)) {
        if (configuredModel != null && configuredModel.provider !in supportedProviders) {
            return ModelResolution.UnsupportedProvider(
                value = EmbeddingsModelSelection.Explicit(configuredModel),
                provider = configuredModel.provider,
            )
        }
        return ModelResolution.Resolved(EmbeddingsModelSelection.Default)
    }
    if (normalized.isEmpty()) return ModelResolution.Unknown(normalized)

    val exactName = EmbeddingsModel.entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) }
    if (exactName != null) {
        return EmbeddingsModelSelection.Explicit(exactName).withProviderSupport(supportedProviders)
    }

    val candidates = EmbeddingsModel.entries.filter { model ->
        model.alias.equals(normalized, ignoreCase = true)
    }
    val selected = when {
        candidates.size == 1 -> candidates.single()
        configuredModel != null && configuredModel in candidates -> configuredModel
        candidates.isEmpty() -> return ModelResolution.Unknown(normalized)
        else -> return ModelResolution.Ambiguous(
            normalizedInput = normalized,
            candidates = candidates.map(EmbeddingsModelSelection::Explicit),
        )
    }
    return EmbeddingsModelSelection.Explicit(selected).withProviderSupport(supportedProviders)
}

private fun LLMModel.withProviderSupport(
    supportedProviders: Set<LlmProvider>,
): ModelResolution<LLMModel> =
    if (provider in supportedProviders) {
        ModelResolution.Resolved(this)
    } else {
        ModelResolution.UnsupportedProvider(this, provider)
    }

private fun EmbeddingsModelSelection.Explicit.withProviderSupport(
    supportedProviders: Set<LlmProvider>,
): ModelResolution<EmbeddingsModelSelection> =
    if (model.provider in supportedProviders) {
        ModelResolution.Resolved(this)
    } else {
        ModelResolution.UnsupportedProvider(this, model.provider)
    }
