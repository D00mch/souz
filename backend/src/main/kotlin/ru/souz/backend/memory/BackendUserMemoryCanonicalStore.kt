package ru.souz.backend.memory

import java.time.Instant
import ru.souz.agent.memory.MemoryDocType
import ru.souz.agent.memory.MemoryEmbeddingDocRecord
import ru.souz.agent.memory.MemoryEntityRecord
import ru.souz.agent.memory.MemoryEpisodeRecord
import ru.souz.agent.memory.MemoryEvidenceRecord
import ru.souz.agent.memory.MemoryFactRecord
import ru.souz.agent.memory.MemoryFactStatus
import ru.souz.agent.memory.MemoryGraphSnapshot
import ru.souz.agent.memory.MemoryScope
import ru.souz.agent.memory.MemoryTriggerType
import ru.souz.memory.MemoryCanonicalStore

class BackendUserMemoryCanonicalStore(
    private val userId: String,
    private val store: BackendMemoryStore,
) : MemoryCanonicalStore {
    override suspend fun resolveOrUpsertEntity(
        entity: MemoryEntityRecord,
        aliases: List<String>,
    ): MemoryEntityRecord = store.resolveOrUpsertEntity(userId, entity, aliases)

    override suspend fun listEntitiesByIds(entityIds: Set<String>): List<MemoryEntityRecord> =
        store.listEntitiesByIds(userId, entityIds)

    override suspend fun insertEvidence(evidence: MemoryEvidenceRecord): MemoryEvidenceRecord =
        store.insertEvidence(userId, evidence)

    override suspend fun insertFact(
        fact: MemoryFactRecord,
        evidenceIds: List<String>,
    ): MemoryFactRecord = store.insertFact(userId, fact, evidenceIds)

    override suspend fun listFacts(
        scope: MemoryScope?,
        statuses: Set<MemoryFactStatus>,
    ): List<MemoryFactRecord> = store.listFacts(userId, scope, statuses)

    override suspend fun insertEpisode(episode: MemoryEpisodeRecord): MemoryEpisodeRecord =
        store.insertEpisode(userId, episode)

    override suspend fun listEpisodes(
        scopes: List<MemoryScope>,
        statuses: Set<String>,
    ): List<MemoryEpisodeRecord> = store.listEpisodes(userId, scopes, statuses)

    override suspend fun replaceEmbeddingDocs(docs: List<MemoryEmbeddingDocRecord>) =
        store.replaceEmbeddingDocs(userId, docs)

    override suspend fun listEmbeddingDocs(
        scopes: List<MemoryScope>,
        docTypes: Set<MemoryDocType>,
        fingerprint: String?,
    ): List<MemoryEmbeddingDocRecord> = store.listEmbeddingDocs(userId, scopes, docTypes, fingerprint)

    override suspend fun logWriteAttempt(
        scope: MemoryScope,
        turnRef: String?,
        triggerType: MemoryTriggerType,
        inputExcerpt: String?,
        candidatesJson: String,
        acceptedCount: Int,
        rejectedCount: Int,
        rejectionReasonsJson: String?,
        createdAt: Instant,
    ): String = store.logWriteAttempt(
        userId = userId,
        scope = scope,
        turnRef = turnRef,
        triggerType = triggerType,
        inputExcerpt = inputExcerpt,
        candidatesJson = candidatesJson,
        acceptedCount = acceptedCount,
        rejectedCount = rejectedCount,
        rejectionReasonsJson = rejectionReasonsJson,
        createdAt = createdAt,
    )

    override suspend fun logInjection(
        scope: MemoryScope,
        turnRef: String?,
        queryExcerpt: String?,
        selectedRecordIds: List<String>,
        renderedPacket: String,
        estimatedTokens: Int,
        createdAt: Instant,
    ): String = store.logInjection(
        userId = userId,
        scope = scope,
        turnRef = turnRef,
        queryExcerpt = queryExcerpt,
        selectedRecordIds = selectedRecordIds,
        renderedPacket = renderedPacket,
        estimatedTokens = estimatedTokens,
        createdAt = createdAt,
    )

    override suspend fun graphSnapshot(scope: MemoryScope): MemoryGraphSnapshot =
        store.graphSnapshot(userId, scope)

    override suspend fun forgetFact(factId: String, at: Instant): Boolean =
        store.forgetFact(userId, factId, at)

    override suspend fun invalidateFact(factId: String, at: Instant): Boolean =
        store.invalidateFact(userId, factId, at)
}
