package ru.souz.backend.storage.filesystem

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Path
import java.util.UUID
import ru.souz.agent.memory.MemoryEntityRecord
import ru.souz.agent.memory.MemoryEvidenceRecord
import ru.souz.agent.memory.MemoryFactRecord
import ru.souz.agent.memory.MemoryFactStatus
import ru.souz.agent.memory.MemoryScope
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

    override suspend fun listActiveFacts(
        userId: String,
        scope: MemoryScope,
    ): List<MemoryFactRecord> = withFileLock {
        readFacts(userId)
            .filter { it.scope == scope && it.status == MemoryFactStatus.ACTIVE }
            .sortedByDescending { it.createdAt }
    }

    private fun readEntities(userId: String): List<MemoryEntityRecord> =
        mapper.readJsonIfExists<List<MemoryEntityRecord>>(layout.memoryEntitiesFile(userId)).orEmpty()

    private fun readEvidence(userId: String): List<MemoryEvidenceRecord> =
        mapper.readJsonIfExists<List<MemoryEvidenceRecord>>(layout.memoryEvidenceFile(userId)).orEmpty()

    private fun readFacts(userId: String): List<MemoryFactRecord> =
        mapper.readJsonIfExists<List<MemoryFactRecord>>(layout.memoryFactsFile(userId)).orEmpty()

    private fun readFactEvidence(userId: String): List<FilesystemFactEvidenceLink> =
        mapper.readJsonIfExists<List<FilesystemFactEvidenceLink>>(layout.memoryFactEvidenceFile(userId)).orEmpty()

    private fun newId(): String = UUID.randomUUID().toString()
}

private data class FilesystemFactEvidenceLink(
    val factId: String,
    val evidenceId: String,
)
