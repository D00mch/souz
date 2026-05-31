package ru.souz.ambient

import kotlinx.coroutines.flow.Flow

interface AmbientBlockAnalyzer {
    suspend fun analyze(
        block: AmbientSemanticBlock,
        recentContext: List<AmbientSemanticBlock>,
        capabilities: List<AmbientCapability>,
    ): AmbientAnalysisResult
}

interface AmbientLocalLlm {
    suspend fun completeJson(systemPrompt: String, userPrompt: String): String
}

data class AmbientAnalysisResult(
    val blockId: String,
    val blockSummary: String?,
    val extractedStatements: List<AmbientExtractedStatement>,
    val taskCandidates: List<AmbientTaskCandidate>,
    val rawModelOutputPreview: String? = null,
)

data class AmbientExtractedStatement(
    val id: String,
    val text: String,
    val kind: AmbientStatementKind,
    val confidence: Double,
    val evidenceEventIds: List<String>,
)

enum class AmbientStatementKind {
    NOTE,
    PREFERENCE,
    FACT,
    QUESTION,
    OTHER,
}

data class AmbientTaskCandidate(
    val id: String,
    val title: String,
    val taskText: String,
    val suggestionText: String,
    val confidence: Double,
    val addressedness: AmbientAddressedness,
    val matchedCapabilityIds: List<String>,
    val missingSlots: List<String>,
    val risk: AmbientTaskRisk,
    val requiresConfirmation: Boolean = true,
    val evidenceEventIds: List<String>,
    val reason: String,
)

enum class AmbientTaskRisk {
    LOW,
    MEDIUM,
    HIGH,
    UNKNOWN,
}

interface AmbientAnalysisService {
    val analyses: Flow<AmbientAnalysisResult>
    val taskCandidates: Flow<AmbientTaskCandidate>
    suspend fun analyzeBlock(block: AmbientSemanticBlock): AmbientAnalysisResult
    suspend fun stop()
    fun recentAnalyses(): List<AmbientAnalysisResult>
    fun clear()
}
