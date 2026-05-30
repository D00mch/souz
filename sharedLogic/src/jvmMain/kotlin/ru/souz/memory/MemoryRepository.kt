package ru.souz.memory

import java.time.Instant

interface MemoryRepository {
    suspend fun insertSourceEvent(input: NewMemorySourceEvent): String

    suspend fun insertFact(
        input: NewMemoryFact,
        evidence: List<MemoryEvidenceRef>,
        embedding: FloatArray? = null,
        embeddingModel: String? = null,
    ): String

    suspend fun getFact(factId: String): MemoryFact?

    suspend fun getFactDetails(factId: String): MemoryFactDetails?

    suspend fun listFacts(filter: MemoryFactFilter): List<MemoryFact>

    suspend fun updateFact(
        fact: MemoryFact,
        expectedUpdatedAt: Instant,
        embedding: FloatArray? = null,
        embeddingModel: String? = null,
    ): MemoryFact

    suspend fun retireFact(factId: String)

    suspend fun deleteFact(factId: String)

    suspend fun deleteSourceEventIfUnused(sourceEventId: String)

    suspend fun findActiveFactBySlotKey(
        scope: MemoryScope,
        slotKey: String,
    ): MemoryFact?

    suspend fun replaceEmbedding(
        factId: String,
        model: String,
        embedding: FloatArray,
    )

    suspend fun searchFacts(
        scopes: List<MemoryScope>,
        model: String,
        queryEmbedding: FloatArray,
        limit: Int,
    ): List<MemoryFactSearchHit>

    suspend fun getFactsWithoutEmbedding(
        scopes: List<MemoryScope>,
        model: String,
        expectedDimension: Int? = null,
        limit: Int,
    ): List<MemoryFact>
}
