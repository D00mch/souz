package ru.souz.agent.memory

import java.time.Instant
import kotlin.math.ceil

enum class MemoryCandidateRejectionReason {
    NO_EVIDENCE_REFS,
    ASSISTANT_ONLY_EVIDENCE,
    BLANK_PREDICATE,
    LOW_CONFIDENCE,
    INVALID_SCOPE,
    UNSUPPORTED_STATUS,
    BLANK_CONFLICT_SLOT_KEY,
    EPHEMERAL,
    DUPLICATE_ACTIVE_FACT,
    TURN_LIMIT_EXCEEDED,
}

data class MemoryCandidateValidationInput(
    val candidate: MemoryCandidate,
    val evidenceIndex: Map<String, MemoryEvidenceRecord> = emptyMap(),
    val activeFacts: List<MemoryFactSnapshot> = emptyList(),
    val acceptedCountThisTurn: Int = 0,
    val allowedScopes: Set<MemoryScope> = emptySet(),
)

data class MemoryCandidateValidationResult(
    val accepted: Boolean,
    val rejectionReason: MemoryCandidateRejectionReason? = null,
)

data class MemoryFactSnapshot(
    val id: String? = null,
    val scope: MemoryScope,
    val subjectEntityType: String,
    val subjectCanonicalName: String,
    val subjectDisplayName: String = subjectCanonicalName,
    val subjectNormalizedKey: String,
    val predicate: String,
    val objectKind: MemoryObjectKind,
    val objectEntityType: String? = null,
    val objectEntityCanonicalName: String? = null,
    val objectEntityDisplayName: String? = objectEntityCanonicalName,
    val objectEntityNormalizedKey: String? = null,
    val objectValueText: String? = null,
    val objectValueJson: String? = null,
    val slotKey: String? = null,
    val confidence: Double = 1.0,
    val status: MemoryFactStatus = MemoryFactStatus.ACTIVE,
    val createdAt: Instant = Instant.EPOCH,
)

data class MemoryEpisodeSnapshot(
    val id: String? = null,
    val scope: MemoryScope,
    val title: String,
    val summary: String,
    val status: String = "ACTIVE",
    val nextAction: String? = null,
    val lastTouchedAt: Instant = Instant.EPOCH,
)

data class MemoryConflictResolution(
    val updatedExistingFacts: List<MemoryFactSnapshot>,
    val supersededFactIds: List<String>,
)

data class MemoryPacketRenderInput(
    val facts: List<MemoryFactSnapshot> = emptyList(),
    val episodes: List<MemoryEpisodeSnapshot> = emptyList(),
    val maxItems: Int = 10,
    val maxChars: Int = 1_200,
)

fun MemoryCandidate.toFactSnapshot(
    id: String? = null,
    createdAt: Instant = Instant.EPOCH,
    status: MemoryFactStatus = suggestedStatus,
): MemoryFactSnapshot = MemoryFactSnapshot(
    id = id,
    scope = scope,
    subjectEntityType = subjectEntityType,
    subjectCanonicalName = subjectCanonicalName,
    subjectDisplayName = subjectDisplayName,
    subjectNormalizedKey = subjectNormalizedKey,
    predicate = predicate,
    objectKind = objectKind,
    objectEntityType = objectEntityType,
    objectEntityCanonicalName = objectEntityCanonicalName,
    objectEntityDisplayName = objectEntityDisplayName,
    objectEntityNormalizedKey = objectEntityNormalizedKey,
    objectValueText = objectValueText,
    objectValueJson = objectValueJson,
    slotKey = slotKey,
    confidence = confidence,
    status = status,
    createdAt = createdAt,
)

internal fun estimateTokenCount(text: String): Int = ceil(text.length / 4.0).toInt()

internal fun normalizeKey(value: String?): String? = value?.trim()?.takeIf(String::isNotEmpty)?.lowercase()

internal fun normalizeText(value: String?): String? = value
    ?.trim()
    ?.replace(Regex("\\s+"), " ")
    ?.takeIf(String::isNotEmpty)

internal fun normalizeScope(scope: MemoryScope): String = "${scope.type}:${scope.id.trim()}"

internal fun MemoryFactSnapshot.renderValue(): String = when {
    !objectEntityDisplayName.isNullOrBlank() -> objectEntityDisplayName.trim()
    !objectValueText.isNullOrBlank() -> normalizeText(objectValueText).orEmpty()
    !objectValueJson.isNullOrBlank() -> objectValueJson.trim()
    else -> objectKind.name.lowercase()
}

internal fun MemoryFactSnapshot.slotGroupingKey(): String? {
    val normalizedSlotKey = normalizeKey(slotKey) ?: return null
    return normalizedSlotKey
}

internal fun MemoryFactSnapshot.duplicateFingerprint(): String = listOf(
    normalizeScope(scope),
    normalizeKey(subjectEntityType).orEmpty(),
    normalizeKey(subjectNormalizedKey).orEmpty(),
    normalizeKey(predicate).orEmpty(),
    objectKind.name,
    normalizeKey(objectEntityType).orEmpty(),
    normalizeKey(objectEntityNormalizedKey).orEmpty(),
    normalizeText(objectValueText)?.lowercase().orEmpty(),
    normalizeText(objectValueJson).orEmpty(),
    normalizeKey(slotKey).orEmpty(),
).joinToString("|")

internal fun MemoryCandidate.duplicateFingerprint(): String = listOf(
    normalizeScope(scope),
    normalizeKey(subjectEntityType).orEmpty(),
    normalizeKey(subjectNormalizedKey).orEmpty(),
    normalizeKey(predicate).orEmpty(),
    objectKind.name,
    normalizeKey(objectEntityType).orEmpty(),
    normalizeKey(objectEntityNormalizedKey).orEmpty(),
    normalizeText(objectValueText)?.lowercase().orEmpty(),
    normalizeText(objectValueJson).orEmpty(),
    normalizeKey(slotKey).orEmpty(),
).joinToString("|")
