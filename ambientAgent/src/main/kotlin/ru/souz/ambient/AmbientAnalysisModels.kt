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

data class AmbientModeState(
    val enabled: Boolean = false,
    val starting: Boolean = false,
    val listening: Boolean = false,
    val analyzing: Boolean = false,
    val errorMessage: String? = null,
)
