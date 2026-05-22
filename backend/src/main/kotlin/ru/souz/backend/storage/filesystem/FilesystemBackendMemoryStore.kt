package ru.souz.backend.storage.filesystem

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import ru.souz.agent.memory.MemoryDocType
import ru.souz.agent.memory.MemoryEmbeddingDocRecord
import ru.souz.agent.memory.MemoryEntityRecord
import ru.souz.agent.memory.MemoryEpisodeRecord
import ru.souz.agent.memory.MemoryEvidenceRecord
import ru.souz.agent.memory.MemoryFactRecord
import ru.souz.agent.memory.MemoryFactStatus
import ru.souz.agent.memory.MemoryGraphSnapshot
import ru.souz.agent.memory.MemoryObjectKind
import ru.souz.agent.memory.MemoryScope
import ru.souz.agent.memory.MemoryTriggerType
import ru.souz.backend.memory.BackendMemoryStore

class FilesystemBackendMemoryStore(
    dataDir: Path,
    mapper: ObjectMapper = filesystemStorageObjectMapper().findAndRegisterModules(),
) : BaseFilesystemRepository(dataDir, mapper), BackendMemoryStore {

    override suspend fun resolveOrUpsertEntity(
        userId: String,
        entity: MemoryEntityRecord,
        aliases: List<String>,
    ): MemoryEntityRecord = withFileLock {
        val current = readEntities(userId).toMutableList()
        val existingIndex = current.indexOfFirst { it.normalizedKey == entity.normalizedKey }
        val stored = when {
            existingIndex >= 0 -> current[existingIndex].copy(
                scope = entity.scope,
                entityType = entity.entityType,
                canonicalName = entity.canonicalName,
                displayName = entity.displayName,
                normalizedKey = entity.normalizedKey,
                status = entity.status,
                updatedAt = entity.updatedAt,
            )

            else -> entity.copy(id = entity.id ?: newId())
        }
        if (existingIndex >= 0) {
            current[existingIndex] = stored
        } else {
            current += stored
        }
        mapper.writeJsonFile(layout.memoryEntitiesFile(userId), current)
        stored
    }

    override suspend fun listEntitiesByIds(
        userId: String,
        entityIds: Set<String>,
    ): List<MemoryEntityRecord> = withFileLock {
        if (entityIds.isEmpty()) return@withFileLock emptyList()
        readEntities(userId).filter { it.id in entityIds }
    }

    override suspend fun insertEvidence(
        userId: String,
        evidence: MemoryEvidenceRecord,
    ): MemoryEvidenceRecord = withFileLock {
        val current = readEvidence(userId).toMutableList()
        val stored = evidence.copy(id = evidence.id ?: newId())
        current += stored
        mapper.writeJsonFile(layout.memoryEvidenceFile(userId), current)
        stored
    }

    override suspend fun insertFact(
        userId: String,
        fact: MemoryFactRecord,
        evidenceIds: List<String>,
    ): MemoryFactRecord = withFileLock {
        val current = readFacts(userId).toMutableList()
        val stored = fact.copy(id = fact.id ?: newId())
        stored.slotKey?.let { slotKey ->
            current.indices.forEach { index ->
                val existing = current[index]
                if (existing.slotKey == slotKey && existing.status == MemoryFactStatus.ACTIVE) {
                    current[index] = existing.copy(
                        status = MemoryFactStatus.SUPERSEDED,
                        invalidatedAt = stored.validFrom,
                        invalidatedByFactId = stored.id,
                    )
                }
            }
        }
        current += stored
        mapper.writeJsonFile(layout.memoryFactsFile(userId), current)

        val links = readFactEvidence(userId).toMutableList()
        links += evidenceIds.distinct().map { evidenceId ->
            FilesystemFactEvidenceLink(
                factId = stored.id!!,
                evidenceId = evidenceId,
            )
        }
        mapper.writeJsonFile(layout.memoryFactEvidenceFile(userId), links)
        stored
    }

    override suspend fun listFacts(
        userId: String,
        scope: MemoryScope?,
        statuses: Set<MemoryFactStatus>,
    ): List<MemoryFactRecord> = withFileLock {
        readFacts(userId)
            .filter { fact ->
                (scope == null || fact.scope == scope) &&
                    fact.status in statuses
            }
            .sortedByDescending { it.createdAt }
    }

    override suspend fun insertEpisode(
        userId: String,
        episode: MemoryEpisodeRecord,
    ): MemoryEpisodeRecord = withFileLock {
        val current = readEpisodes(userId).toMutableList()
        val stored = episode.copy(id = episode.id ?: newId())
        current += stored
        mapper.writeJsonFile(layout.memoryEpisodesFile(userId), current)
        stored
    }

    override suspend fun listEpisodes(
        userId: String,
        scopes: List<MemoryScope>,
        statuses: Set<String>,
    ): List<MemoryEpisodeRecord> = withFileLock {
        readEpisodes(userId)
            .filter { episode ->
                (scopes.isEmpty() || episode.scope in scopes) &&
                    episode.status in statuses
            }
            .sortedByDescending { it.lastTouchedAt }
    }

    override suspend fun replaceEmbeddingDocs(
        userId: String,
        docs: List<MemoryEmbeddingDocRecord>,
    ) = withFileLock {
        mapper.writeJsonFile(
            layout.memoryEmbeddingDocsFile(userId),
            docs.map { doc -> doc.copy(id = doc.id ?: newId()) },
        )
    }

    override suspend fun listEmbeddingDocs(
        userId: String,
        scopes: List<MemoryScope>,
        docTypes: Set<MemoryDocType>,
        fingerprint: String?,
    ): List<MemoryEmbeddingDocRecord> = withFileLock {
        readEmbeddingDocs(userId)
            .filter { doc ->
                doc.status == "ACTIVE" &&
                    (scopes.isEmpty() || doc.scope in scopes) &&
                    (docTypes.isEmpty() || doc.docType in docTypes) &&
                    (fingerprint == null || doc.embeddingModelFingerprint == fingerprint)
            }
            .sortedByDescending { it.updatedAt }
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
    ): String = withFileLock {
        val current = readScopedDiagnostics(layout.memoryWriteAttemptsFile(userId)).toMutableList()
        val id = newId()
        current += ScopedDiagnostic(scope = scope, id = id)
        mapper.writeJsonFile(layout.memoryWriteAttemptsFile(userId), current)
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
    ): String = withFileLock {
        val current = readScopedDiagnostics(layout.memoryInjectionLogsFile(userId)).toMutableList()
        val id = newId()
        current += ScopedDiagnostic(scope = scope, id = id)
        mapper.writeJsonFile(layout.memoryInjectionLogsFile(userId), current)
        id
    }

    override suspend fun graphSnapshot(
        userId: String,
        scope: MemoryScope,
    ): MemoryGraphSnapshot = withFileLock {
        val facts = readFacts(userId)
            .filter { it.scope == scope && it.status == MemoryFactStatus.ACTIVE }
            .sortedByDescending { it.createdAt }
        val entities = readEntities(userId).associateBy { it.id }
        val evidenceById = readEvidence(userId).associateBy { it.id }
        val links = readFactEvidence(userId)
        MemoryGraphSnapshot(
            entities = facts.flatMap { listOfNotNull(it.subjectEntityId, it.objectEntityId) }.distinct().mapNotNull(entities::get),
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
            timelineEvents = readFacts(userId)
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
            evidenceIndex = facts.associate { fact ->
                fact.id!! to links
                    .filter { it.factId == fact.id }
                    .mapNotNull { link -> evidenceById[link.evidenceId] }
            },
            diagnostics = MemoryGraphSnapshot.Diagnostics(
                writeAttemptCount = readScopedDiagnostics(layout.memoryWriteAttemptsFile(userId)).count { it.scope == scope },
                injectionLogCount = readScopedDiagnostics(layout.memoryInjectionLogsFile(userId)).count { it.scope == scope },
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

    private fun readEntities(userId: String): List<MemoryEntityRecord> =
        mapper.readJsonIfExists<List<MemoryEntityRecord>>(layout.memoryEntitiesFile(userId)).orEmpty()

    private fun readEvidence(userId: String): List<MemoryEvidenceRecord> =
        mapper.readJsonIfExists<List<MemoryEvidenceRecord>>(layout.memoryEvidenceFile(userId)).orEmpty()

    private fun readFacts(userId: String): List<MemoryFactRecord> =
        mapper.readJsonIfExists<List<MemoryFactRecord>>(layout.memoryFactsFile(userId)).orEmpty()

    private fun readFactEvidence(userId: String): List<FilesystemFactEvidenceLink> =
        mapper.readJsonIfExists<List<FilesystemFactEvidenceLink>>(layout.memoryFactEvidenceFile(userId)).orEmpty()

    private fun readEpisodes(userId: String): List<MemoryEpisodeRecord> =
        mapper.readJsonIfExists<List<MemoryEpisodeRecord>>(layout.memoryEpisodesFile(userId)).orEmpty()

    private fun readEmbeddingDocs(userId: String): List<MemoryEmbeddingDocRecord> =
        mapper.readJsonIfExists<List<MemoryEmbeddingDocRecord>>(layout.memoryEmbeddingDocsFile(userId)).orEmpty()

    private fun readScopedDiagnostics(path: Path): List<ScopedDiagnostic> =
        mapper.readJsonIfExists<List<ScopedDiagnostic>>(path).orEmpty()

    private suspend fun updateFactStatus(
        userId: String,
        factId: String,
        status: MemoryFactStatus,
        at: Instant,
    ): Boolean = withFileLock {
        val current = readFacts(userId).toMutableList()
        val index = current.indexOfFirst { it.id == factId && it.status == MemoryFactStatus.ACTIVE }
        if (index < 0) return@withFileLock false
        current[index] = current[index].copy(
            status = status,
            invalidatedAt = at,
        )
        mapper.writeJsonFile(layout.memoryFactsFile(userId), current)
        true
    }

    private fun newId(): String = UUID.randomUUID().toString()
}

private data class FilesystemFactEvidenceLink(
    val factId: String,
    val evidenceId: String,
)

private data class ScopedDiagnostic(
    val scope: MemoryScope,
    val id: String,
)
