package ru.souz.memory

import java.time.Instant
import ru.souz.agent.memory.MemoryDocType
import ru.souz.agent.memory.MemoryEmbeddingDocRecord
import ru.souz.agent.memory.MemoryEntityRecord
import ru.souz.agent.memory.MemoryEpisodeRecord
import ru.souz.agent.memory.MemoryEvidenceRecord
import ru.souz.agent.memory.MemoryFactRecord
import ru.souz.agent.memory.MemoryFactStatus
import ru.souz.agent.memory.MemoryGraphQueryService
import ru.souz.agent.memory.MemoryScope
import ru.souz.agent.memory.MemoryTriggerType

interface MemoryCanonicalStore : MemoryGraphQueryService {
    suspend fun resolveOrUpsertEntity(
        entity: MemoryEntityRecord,
        aliases: List<String> = emptyList(),
    ): MemoryEntityRecord

    suspend fun listEntitiesByIds(entityIds: Set<String>): List<MemoryEntityRecord>

    suspend fun insertEvidence(evidence: MemoryEvidenceRecord): MemoryEvidenceRecord

    suspend fun insertFact(
        fact: MemoryFactRecord,
        evidenceIds: List<String>,
    ): MemoryFactRecord

    suspend fun listFacts(
        scope: MemoryScope? = null,
        statuses: Set<MemoryFactStatus> = setOf(MemoryFactStatus.ACTIVE),
    ): List<MemoryFactRecord>

    suspend fun insertEpisode(episode: MemoryEpisodeRecord): MemoryEpisodeRecord

    suspend fun listEpisodes(
        scopes: List<MemoryScope> = emptyList(),
        statuses: Set<String> = setOf("ACTIVE"),
    ): List<MemoryEpisodeRecord>

    suspend fun replaceEmbeddingDocs(docs: List<MemoryEmbeddingDocRecord>)

    suspend fun listEmbeddingDocs(
        scopes: List<MemoryScope> = emptyList(),
        docTypes: Set<MemoryDocType> = emptySet(),
        fingerprint: String? = null,
    ): List<MemoryEmbeddingDocRecord>

    suspend fun logWriteAttempt(
        scope: MemoryScope,
        turnRef: String?,
        triggerType: MemoryTriggerType,
        inputExcerpt: String?,
        candidatesJson: String,
        acceptedCount: Int,
        rejectedCount: Int,
        rejectionReasonsJson: String?,
        createdAt: Instant,
    ): String

    suspend fun logInjection(
        scope: MemoryScope,
        turnRef: String?,
        queryExcerpt: String?,
        selectedRecordIds: List<String>,
        renderedPacket: String,
        estimatedTokens: Int,
        createdAt: Instant,
    ): String

    suspend fun forgetFact(factId: String, at: Instant): Boolean

    suspend fun invalidateFact(factId: String, at: Instant): Boolean
}
