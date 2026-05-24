package ru.souz.memory

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import kotlin.math.sqrt

data class MemoryScope(
    val type: String,
    val id: String,
)

enum class MemoryFactKind {
    SEMANTIC,
    PREFERENCE,
    PROCEDURE,
    PROJECT_RULE,
    EPISODE_NOTE,
    PROJECT_DECISION,
}

enum class MemoryFactStatus {
    ACTIVE,
    RETIRED,
    DELETED,
}

data class MemorySourceEvent(
    val id: String,
    val scope: MemoryScope,
    val sourceType: String,
    val sourceRef: String?,
    val text: String,
    val metadataJson: String,
    val createdAt: Instant,
)

data class MemoryFact(
    val id: String,
    val scope: MemoryScope,
    val kind: MemoryFactKind,
    val title: String,
    val body: String,
    val slotKey: String?,
    val status: MemoryFactStatus,
    val confidence: Float,
    val pinned: Boolean,
    val createdBy: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val supersedesFactId: String?,
)

data class MemoryEvidence(
    val factId: String,
    val sourceEventId: String,
    val evidenceText: String?,
)

data class MemoryFactSearchHit(
    val fact: MemoryFact,
    val score: Float,
)

data class MemoryBlock(
    val facts: List<MemoryFact>,
    val rendered: String,
)

data class MemoryEvidenceRef(
    val sourceEventId: String,
    val evidenceText: String?,
)

data class MemoryEvidenceDetail(
    val evidence: MemoryEvidence,
    val sourceEvent: MemorySourceEvent,
)

data class MemoryFactDetails(
    val fact: MemoryFact,
    val evidence: List<MemoryEvidenceDetail>,
)

data class NewMemorySourceEvent(
    val scope: MemoryScope,
    val sourceType: String,
    val sourceRef: String?,
    val text: String,
    val metadataJson: String = "{}",
    val createdAt: Instant = Instant.now(),
)

data class NewMemoryFact(
    val scope: MemoryScope,
    val kind: MemoryFactKind,
    val title: String,
    val body: String,
    val slotKey: String?,
    val status: MemoryFactStatus,
    val confidence: Float,
    val pinned: Boolean,
    val createdBy: String,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = createdAt,
    val supersedesFactId: String?,
)

data class CreateMemoryFactInput(
    val scope: MemoryScope,
    val kind: MemoryFactKind,
    val title: String,
    val body: String,
    val slotKey: String? = null,
    val confidence: Float = 1f,
    val pinned: Boolean = false,
)

data class CreateCapturedFactInput(
    val scope: MemoryScope,
    val kind: MemoryFactKind,
    val title: String,
    val body: String,
    val slotKey: String?,
    val confidence: Float,
    val evidenceText: String,
    val sourceEventId: String,
    val pinned: Boolean = false,
)

data class MemoryFactPatch(
    val scope: MemoryScope? = null,
    val kind: MemoryFactKind? = null,
    val title: String? = null,
    val body: String? = null,
    val slotKey: String? = null,
    val clearSlotKey: Boolean = false,
    val confidence: Float? = null,
    val pinned: Boolean? = null,
)

data class MemoryFactFilter(
    val statuses: Set<MemoryFactStatus> = setOf(MemoryFactStatus.ACTIVE),
    val kinds: Set<MemoryFactKind> = emptySet(),
    val scope: MemoryScope? = null,
    val query: String? = null,
    val limit: Int = 100,
    val offset: Int = 0,
)

data class MemoryCaptureInput(
    val scopes: List<MemoryScope>,
    val primaryScope: MemoryScope,
    val userMessage: String,
    val assistantMessage: String,
    val conversationId: String?,
    val userMessageId: String?,
    val assistantMessageId: String?,
)

data class MemoryFactCandidate(
    val shouldSave: Boolean,
    val kind: MemoryFactKind,
    val title: String,
    val body: String,
    val scope: MemoryScope?,
    val slotKey: String?,
    val confidence: Float,
    val evidenceText: String,
)

fun FloatArray.toBlob(): ByteArray {
    val buffer = ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN)
    forEach(buffer::putFloat)
    return buffer.array()
}

fun ByteArray.toFloatArray(): FloatArray {
    val buffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(size / 4) { buffer.getFloat() }
}

fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
    var dot = 0f
    var normA = 0f
    var normB = 0f

    val size = minOf(a.size, b.size)
    for (index in 0 until size) {
        dot += a[index] * b[index]
        normA += a[index] * a[index]
        normB += b[index] * b[index]
    }

    if (normA == 0f || normB == 0f) return 0f
    return dot / (sqrt(normA) * sqrt(normB))
}
