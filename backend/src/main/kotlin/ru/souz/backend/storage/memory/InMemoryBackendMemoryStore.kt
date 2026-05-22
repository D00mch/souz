package ru.souz.backend.storage.memory

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import ru.souz.agent.memory.MemoryObjectKind
import ru.souz.backend.memory.BackendMemoryStore

class InMemoryBackendMemoryStore(
    maxEntries: Int = DEFAULT_MEMORY_REPOSITORY_MAX_ENTRIES,
) : BackendMemoryStore {
    private val mutex = Mutex()
    private val states = boundedLruMap<String, UserMemoryState>(maxEntries)

    override suspend fun resolveOrUpsertEntity(
        userId: String,
        entity: MemoryEntityRecord,
        aliases: List<String>,
    ): MemoryEntityRecord = mutex.withLock {
        val state = states.getOrPut(userId) { UserMemoryState() }
        val existingId = state.entityIdsByNormalizedKey[entity.normalizedKey]
        val existing = existingId?.let(state.entitiesById::get)
        val stored = (existing ?: entity.copy(id = entity.id ?: newId())).copy(
            scope = entity.scope,
            entityType = entity.entityType,
            canonicalName = entity.canonicalName,
            displayName = entity.displayName,
            normalizedKey = entity.normalizedKey,
            status = entity.status,
            updatedAt = entity.updatedAt,
        )
        val storedId = checkNotNull(stored.id)
        state.entitiesById[storedId] = stored
        state.entityIdsByNormalizedKey[stored.normalizedKey] = storedId
        stored
    }

    override suspend fun listEntitiesByIds(
        userId: String,
        entityIds: Set<String>,
    ): List<MemoryEntityRecord> = mutex.withLock {
        val state = states[userId] ?: return@withLock emptyList()
        entityIds.mapNotNull(state.entitiesById::get)
    }

    override suspend fun insertEvidence(
        userId: String,
        evidence: MemoryEvidenceRecord,
    ): MemoryEvidenceRecord = mutex.withLock {
        val state = states.getOrPut(userId) { UserMemoryState() }
        val stored = evidence.copy(id = evidence.id ?: newId())
        state.evidenceById[stored.id!!] = stored
        stored
    }

    override suspend fun insertFact(
        userId: String,
        fact: MemoryFactRecord,
        evidenceIds: List<String>,
    ): MemoryFactRecord = mutex.withLock {
        val state = states.getOrPut(userId) { UserMemoryState() }
        val stored = fact.copy(id = fact.id ?: newId())
        val storedId = checkNotNull(stored.id)
        stored.slotKey?.let { slotKey ->
            state.factsById.values
                .filter { it.slotKey == slotKey && it.status == MemoryFactStatus.ACTIVE }
                .forEach { active ->
                    state.factsById[active.id!!] = active.copy(
                        status = MemoryFactStatus.SUPERSEDED,
                        invalidatedAt = stored.validFrom,
                        invalidatedByFactId = stored.id,
                    )
                }
        }
        state.factsById[storedId] = stored
        state.factEvidence[storedId] = evidenceIds.distinct()
        stored
    }

    override suspend fun listFacts(
        userId: String,
        scope: MemoryScope?,
        statuses: Set<MemoryFactStatus>,
    ): List<MemoryFactRecord> = mutex.withLock {
        states[userId]
            ?.factsById
            ?.values
            ?.filter { fact ->
                (scope == null || fact.scope == scope) &&
                    fact.status in statuses
            }
            ?.sortedByDescending { it.createdAt }
            .orEmpty()
    }

    override suspend fun insertEpisode(
        userId: String,
        episode: MemoryEpisodeRecord,
    ): MemoryEpisodeRecord = mutex.withLock {
        val state = states.getOrPut(userId) { UserMemoryState() }
        val stored = episode.copy(id = episode.id ?: newId())
        state.episodesById[stored.id!!] = stored
        stored
    }

    override suspend fun listEpisodes(
        userId: String,
        scopes: List<MemoryScope>,
        statuses: Set<String>,
    ): List<MemoryEpisodeRecord> = mutex.withLock {
        states[userId]
            ?.episodesById
            ?.values
            ?.filter { episode ->
                (scopes.isEmpty() || episode.scope in scopes) &&
                    episode.status in statuses
            }
            ?.sortedByDescending { it.lastTouchedAt }
            .orEmpty()
    }

    override suspend fun replaceEmbeddingDocs(
        userId: String,
        docs: List<MemoryEmbeddingDocRecord>,
    ) = mutex.withLock {
        val state = states.getOrPut(userId) { UserMemoryState() }
        state.embeddingDocsById.clear()
        docs.forEach { doc ->
            val stored = doc.copy(id = doc.id ?: newId())
            state.embeddingDocsById[stored.id!!] = stored
        }
    }

    override suspend fun listEmbeddingDocs(
        userId: String,
        scopes: List<MemoryScope>,
        docTypes: Set<MemoryDocType>,
        fingerprint: String?,
    ): List<MemoryEmbeddingDocRecord> = mutex.withLock {
        states[userId]
            ?.embeddingDocsById
            ?.values
            ?.filter { doc ->
                doc.status == "ACTIVE" &&
                    (scopes.isEmpty() || doc.scope in scopes) &&
                    (docTypes.isEmpty() || doc.docType in docTypes) &&
                    (fingerprint == null || doc.embeddingModelFingerprint == fingerprint)
            }
            ?.sortedByDescending { it.updatedAt }
            .orEmpty()
    }

    override suspend fun logWriteAttempt(
        userId: String,
        scope: MemoryScope,
        turnRef: String?,
        triggerType: MemoryTriggerType,
        inputExcerpt: String?,
        candidatesJson: String,
        acceptedCount: Int,
        rejectedCount: Int,
        rejectionReasonsJson: String?,
        createdAt: Instant,
    ): String = mutex.withLock {
        val state = states.getOrPut(userId) { UserMemoryState() }
        val id = newId()
        state.writeAttempts += ScopedDiagnostic(scope = scope, id = id)
        id
    }

    override suspend fun logInjection(
        userId: String,
        scope: MemoryScope,
        turnRef: String?,
        queryExcerpt: String?,
        selectedRecordIds: List<String>,
        renderedPacket: String,
        estimatedTokens: Int,
        createdAt: Instant,
    ): String = mutex.withLock {
        val state = states.getOrPut(userId) { UserMemoryState() }
        val id = newId()
        state.injectionLogs += ScopedDiagnostic(scope = scope, id = id)
        id
    }

    override suspend fun graphSnapshot(
        userId: String,
        scope: MemoryScope,
    ): MemoryGraphSnapshot = mutex.withLock {
        val state = states[userId] ?: return@withLock MemoryGraphSnapshot()
        val facts = state.factsById.values
            .filter { it.scope == scope && it.status == MemoryFactStatus.ACTIVE }
            .sortedByDescending { it.createdAt }
        val entityIds = facts.flatMap { listOfNotNull(it.subjectEntityId, it.objectEntityId) }.toSet()
        val entities = entityIds.mapNotNull(state.entitiesById::get)
        val evidenceIndex = facts.associate { fact ->
            fact.id!! to state.factEvidence[fact.id].orEmpty().mapNotNull(state.evidenceById::get)
        }
        MemoryGraphSnapshot(
            entities = entities,
            facts = facts,
            edges = facts.mapNotNull { fact ->
                val objectEntityId = fact.objectEntityId ?: return@mapNotNull null
                if (fact.objectKind != MemoryObjectKind.ENTITY) return@mapNotNull null
                MemoryGraphSnapshot.Edge(
                    factId = fact.id!!,
                    subjectEntityId = fact.subjectEntityId,
                    predicate = fact.predicate,
                    objectEntityId = objectEntityId,
                )
            },
            attributes = facts.mapNotNull { fact ->
                if (fact.objectKind == MemoryObjectKind.ENTITY) return@mapNotNull null
                MemoryGraphSnapshot.Attribute(
                    factId = fact.id!!,
                    subjectEntityId = fact.subjectEntityId,
                    predicate = fact.predicate,
                    value = fact.objectValueText ?: fact.objectValueJson.orEmpty(),
                )
            },
            timelineEvents = state.factsById.values
                .filter { it.scope == scope && it.invalidatedByFactId != null && it.invalidatedAt != null }
                .sortedByDescending { it.invalidatedAt }
                .map {
                    MemoryGraphSnapshot.TimelineEvent(
                        type = "SUPERSEDED",
                        factId = it.id!!,
                        relatedFactId = it.invalidatedByFactId,
                        happenedAt = it.invalidatedAt!!,
                    )
                },
            evidenceIndex = evidenceIndex,
            diagnostics = MemoryGraphSnapshot.Diagnostics(
                writeAttemptCount = state.writeAttempts.count { it.scope == scope },
                injectionLogCount = state.injectionLogs.count { it.scope == scope },
            ),
            generatedAt = Instant.now(),
        )
    }

    override suspend fun forgetFact(
        userId: String,
        factId: String,
        at: Instant,
    ): Boolean = updateFactStatus(userId, factId, MemoryFactStatus.FORGOTTEN, at)

    override suspend fun invalidateFact(
        userId: String,
        factId: String,
        at: Instant,
    ): Boolean = updateFactStatus(userId, factId, MemoryFactStatus.INVALIDATED, at)

    private fun newId(): String = UUID.randomUUID().toString()

    private suspend fun updateFactStatus(
        userId: String,
        factId: String,
        status: MemoryFactStatus,
        at: Instant,
    ): Boolean = mutex.withLock {
        val state = states[userId] ?: return@withLock false
        val current = state.factsById[factId] ?: return@withLock false
        if (current.status != MemoryFactStatus.ACTIVE) return@withLock false
        state.factsById[factId] = current.copy(
            status = status,
            invalidatedAt = at,
        )
        true
    }

    private data class UserMemoryState(
        val entitiesById: MutableMap<String, MemoryEntityRecord> = linkedMapOf(),
        val entityIdsByNormalizedKey: MutableMap<String, String> = linkedMapOf(),
        val evidenceById: MutableMap<String, MemoryEvidenceRecord> = linkedMapOf(),
        val factsById: MutableMap<String, MemoryFactRecord> = linkedMapOf(),
        val factEvidence: MutableMap<String, List<String>> = linkedMapOf(),
        val episodesById: MutableMap<String, MemoryEpisodeRecord> = linkedMapOf(),
        val embeddingDocsById: MutableMap<String, MemoryEmbeddingDocRecord> = linkedMapOf(),
        val writeAttempts: MutableList<ScopedDiagnostic> = mutableListOf(),
        val injectionLogs: MutableList<ScopedDiagnostic> = mutableListOf(),
    )

    private data class ScopedDiagnostic(
        val scope: MemoryScope,
        val id: String,
    )
}
