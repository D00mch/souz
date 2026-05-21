package ru.souz.backend.storage.memory

import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.agent.memory.MemoryEntityRecord
import ru.souz.agent.memory.MemoryEvidenceRecord
import ru.souz.agent.memory.MemoryFactRecord
import ru.souz.agent.memory.MemoryFactStatus
import ru.souz.agent.memory.MemoryScope
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

    override suspend fun listActiveFacts(
        userId: String,
        scope: MemoryScope,
    ): List<MemoryFactRecord> = mutex.withLock {
        states[userId]
            ?.factsById
            ?.values
            ?.filter { it.scope == scope && it.status == MemoryFactStatus.ACTIVE }
            ?.sortedByDescending { it.createdAt }
            .orEmpty()
    }

    private fun newId(): String = UUID.randomUUID().toString()

    private data class UserMemoryState(
        val entitiesById: MutableMap<String, MemoryEntityRecord> = linkedMapOf(),
        val entityIdsByNormalizedKey: MutableMap<String, String> = linkedMapOf(),
        val evidenceById: MutableMap<String, MemoryEvidenceRecord> = linkedMapOf(),
        val factsById: MutableMap<String, MemoryFactRecord> = linkedMapOf(),
        val factEvidence: MutableMap<String, List<String>> = linkedMapOf(),
    )
}
