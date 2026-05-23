package ru.souz.agent.memory

import java.time.Instant

enum class MemoryScopeType {
    GLOBAL,
    USER,
    PROJECT,
    WORKSPACE,
    CHAT,
    THREAD,
    EPISODE,
}

enum class MemoryObjectKind {
    ENTITY,
    TEXT,
    NUMBER,
    BOOLEAN,
    JSON,
}

enum class MemoryFactStatus {
    ACTIVE,
    SUPERSEDED,
    FORGOTTEN,
    INVALIDATED,
}

enum class MemoryConflictPolicy {
    ALLOW_MULTIPLE_ACTIVE,
    SINGLE_ACTIVE_PER_SLOT,
}

enum class MemoryEvidenceType {
    USER_MESSAGE,
    ASSISTANT_MESSAGE,
    TOOL_OUTPUT,
    FILE_EXCERPT,
    WEB_EXCERPT,
    SYSTEM_METADATA,
    EPISODE_SUMMARY,
}

enum class MemoryDocType {
    FACT,
    EPISODE,
    PROFILE,
}

enum class MemoryTriggerType {
    EXPLICIT_REMEMBER_REQUEST,
    USER_PROFILE_SIGNAL,
    PROJECT_DECISION,
    TASK_STATE_CHANGE,
    TOOL_CONFIRMED_WORLD_CHANGE,
    EPISODE_COMPLETED,
    PERIODIC_EPISODE_SNAPSHOT,
    CORRECTION_OF_PREVIOUS_FACT,
}

data class MemoryScope(
    val type: MemoryScopeType,
    val id: String,
)

data class MemoryEntityRecord(
    val id: String? = null,
    val scope: MemoryScope,
    val entityType: String,
    val canonicalName: String,
    val displayName: String = canonicalName,
    val normalizedKey: String,
    val status: String = "ACTIVE",
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = createdAt,
)

data class MemoryFactRecord(
    val id: String? = null,
    val scope: MemoryScope,
    val subjectEntityId: String,
    val predicate: String,
    val objectKind: MemoryObjectKind,
    val objectEntityId: String? = null,
    val objectValueText: String? = null,
    val objectValueJson: String? = null,
    val slotKey: String? = null,
    val confidence: Double = 1.0,
    val status: MemoryFactStatus = MemoryFactStatus.ACTIVE,
    val reasonToStore: String = "",
    val createdAt: Instant = Instant.now(),
    val validFrom: Instant = createdAt,
    val invalidatedAt: Instant? = null,
    val invalidatedByFactId: String? = null,
    val originEpisodeId: String? = null,
    val writerVersion: String? = null,
)

data class MemoryEvidenceRecord(
    val id: String? = null,
    val scope: MemoryScope,
    val evidenceType: MemoryEvidenceType,
    val sourceRef: String,
    val sourceHash: String? = null,
    val contentExcerpt: String? = null,
    val contentJson: String? = null,
    val createdAt: Instant = Instant.now(),
)

data class MemoryEpisodeRecord(
    val id: String? = null,
    val scope: MemoryScope,
    val title: String,
    val summary: String,
    val status: String = "ACTIVE",
    val startedAt: Instant = Instant.now(),
    val endedAt: Instant? = null,
    val lastTouchedAt: Instant = startedAt,
    val nextAction: String? = null,
    val importance: Double? = null,
)

data class MemoryEmbeddingDocRecord(
    val id: String? = null,
    val docType: MemoryDocType,
    val sourceRecordType: String,
    val sourceRecordId: String,
    val scope: MemoryScope,
    val text: String,
    val status: String = "ACTIVE",
    val embeddingModelFingerprint: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = createdAt,
)

data class MemoryCandidate(
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
    val scope: MemoryScope,
    val slotKey: String? = null,
    val confidence: Double,
    val reasonToStore: String,
    val evidenceRefs: List<String>,
    val suggestedStatus: MemoryFactStatus = MemoryFactStatus.ACTIVE,
    val conflictPolicy: MemoryConflictPolicy = MemoryConflictPolicy.ALLOW_MULTIPLE_ACTIVE,
)

data class MemoryWriteInput(
    val userMessage: String? = null,
    val assistantMessage: String? = null,
    val toolOutputs: List<String> = emptyList(),
    val scope: MemoryScope,
    val turnRef: String? = null,
    val triggerType: MemoryTriggerType,
    val recentFacts: List<MemoryFactRecord> = emptyList(),
    val recentEpisodeSummary: String? = null,
)

data class MemoryWriteResult(
    val acceptedFacts: List<MemoryFactRecord> = emptyList(),
    val rejectedCount: Int = 0,
    val writeAttemptId: String? = null,
)

data class MemoryInjectionRequest(
    val queryText: String,
    val scope: MemoryScope,
    val turnRef: String? = null,
    val maxItems: Int = 10,
)

data class MemoryInjectionResult(
    val packets: List<MemoryPacket> = emptyList(),
    val renderedBlock: String = "",
    val selectedRecordIds: List<String> = emptyList(),
    val estimatedTokens: Int = 0,
    val debugSummary: String = "",
)

data class MemoryPacket(
    val recordId: String,
    val text: String,
)

data class MemoryGraphSnapshot(
    val entities: List<MemoryEntityRecord> = emptyList(),
    val facts: List<MemoryFactRecord> = emptyList(),
    val edges: List<Edge> = emptyList(),
    val attributes: List<Attribute> = emptyList(),
    val timelineEvents: List<TimelineEvent> = emptyList(),
    val evidenceIndex: Map<String, List<MemoryEvidenceRecord>> = emptyMap(),
    val diagnostics: Diagnostics = Diagnostics(),
    val generatedAt: Instant = Instant.now(),
) {
    data class Edge(
        val factId: String,
        val subjectEntityId: String,
        val predicate: String,
        val objectEntityId: String,
    )

    data class Attribute(
        val factId: String,
        val subjectEntityId: String,
        val predicate: String,
        val value: String,
    )

    data class TimelineEvent(
        val type: String,
        val factId: String,
        val relatedFactId: String? = null,
        val happenedAt: Instant,
    )

    data class Diagnostics(
        val writeAttemptCount: Int = 0,
        val injectionLogCount: Int = 0,
    )
}

interface MemoryWriteService {
    suspend fun write(input: MemoryWriteInput): MemoryWriteResult
}

interface MemoryRetrievalService {
    suspend fun inject(request: MemoryInjectionRequest): MemoryInjectionResult
}

interface MemoryGraphQueryService {
    suspend fun graphSnapshot(scope: MemoryScope): MemoryGraphSnapshot
}

interface MemoryMaintenanceService {
    suspend fun forgetFact(factId: String, at: Instant = Instant.now()): Boolean
    suspend fun invalidateFact(factId: String, at: Instant = Instant.now()): Boolean
    suspend fun rebuildProjection()
}

interface MemoryRuntimeServicesContract :
    MemoryWriteService,
    MemoryRetrievalService,
    MemoryGraphQueryService,
    MemoryMaintenanceService

object NoOpMemoryRuntimeServices :
    MemoryRuntimeServicesContract {
    override suspend fun write(input: MemoryWriteInput): MemoryWriteResult = MemoryWriteResult()

    override suspend fun inject(request: MemoryInjectionRequest): MemoryInjectionResult = MemoryInjectionResult()

    override suspend fun graphSnapshot(scope: MemoryScope): MemoryGraphSnapshot = MemoryGraphSnapshot()

    override suspend fun forgetFact(factId: String, at: Instant): Boolean = false

    override suspend fun invalidateFact(factId: String, at: Instant): Boolean = false

    override suspend fun rebuildProjection() = Unit
}
