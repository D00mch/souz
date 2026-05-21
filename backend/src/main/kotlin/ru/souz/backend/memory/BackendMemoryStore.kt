package ru.souz.backend.memory

import ru.souz.agent.memory.MemoryEntityRecord
import ru.souz.agent.memory.MemoryEvidenceRecord
import ru.souz.agent.memory.MemoryFactRecord
import ru.souz.agent.memory.MemoryScope

interface BackendMemoryStore {
    suspend fun resolveOrUpsertEntity(
        userId: String,
        entity: MemoryEntityRecord,
        aliases: List<String> = emptyList(),
    ): MemoryEntityRecord

    suspend fun insertEvidence(
        userId: String,
        evidence: MemoryEvidenceRecord,
    ): MemoryEvidenceRecord

    suspend fun insertFact(
        userId: String,
        fact: MemoryFactRecord,
        evidenceIds: List<String>,
    ): MemoryFactRecord

    suspend fun listActiveFacts(
        userId: String,
        scope: MemoryScope,
    ): List<MemoryFactRecord>
}
