package ru.souz.ambient

interface AmbientBlockAnalyzer {
    suspend fun analyze(block: AmbientSemanticBlock): AmbientTaskCandidate?
}

interface AmbientLocalLlm {
    suspend fun complete(systemPrompt: String, userPrompt: String): String
}

data class AmbientTaskCandidate(
    val id: String,
    val taskText: String,
    val addressedness: AmbientAddressedness,
    val confidence: Double,
    val evidenceEventIds: List<String>,
)

data class AmbientSuggestion(
    val id: String,
    val candidate: AmbientTaskCandidate,
    val createdAtMs: Long,
    val expiresAtMs: Long,
)

data class AmbientSuggestionConfig(
    val maxPendingSuggestions: Int = 3,
    val ttlMs: Long = 10_000L,
    val dedupeCooldownMs: Long = 2 * 60 * 1_000L,
)

data class AmbientModeState(
    val enabled: Boolean = false,
    val starting: Boolean = false,
    val listening: Boolean = false,
    val analyzing: Boolean = false,
    val errorMessage: String? = null,
)
