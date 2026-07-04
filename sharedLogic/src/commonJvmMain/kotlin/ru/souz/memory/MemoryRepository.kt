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

    suspend fun deleteFactsByScope(ownerId: MemoryOwnerId, scope: MemoryScope) {
        listFacts(
            MemoryFactFilter(
                ownerId = ownerId,
                scope = scope.normalized(),
                statuses = setOf(MemoryFactStatus.ACTIVE, MemoryFactStatus.RETIRED),
                limit = 1_000,
            )
        ).forEach { fact -> deleteFact(fact.id) }
    }

    suspend fun deleteSourceEventIfUnused(sourceEventId: String)

    suspend fun findActiveFactBySlotKey(
        scope: MemoryScope,
        slotKey: String,
    ): MemoryFact?

    suspend fun findActiveFactByCanonicalKey(
        ownerId: MemoryOwnerId,
        scope: MemoryScope,
        canonicalKey: String,
    ): MemoryFact?

    suspend fun lexicalSearchFacts(
        ownerId: MemoryOwnerId,
        scopes: List<MemoryScope>,
        query: String,
        limit: Int,
    ): List<MemoryFactSearchHit> = emptyList()

    suspend fun replaceEmbedding(
        factId: String,
        model: String,
        embedding: FloatArray,
        contentHash: String? = null,
    )

    suspend fun searchFacts(
        ownerId: MemoryOwnerId = MemoryOwnerId(LEGACY_OWNER_ID),
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

    suspend fun enqueueEmbeddingJob(
        factId: String,
        ownerId: MemoryOwnerId,
        model: String,
        contentHash: String,
    ) = Unit

    suspend fun markEmbeddingJobCompleted(
        factId: String,
        model: String,
        contentHash: String,
    ) = Unit

    suspend fun markEmbeddingJobFailed(
        factId: String,
        model: String,
        contentHash: String,
        errorCode: String,
    ) = Unit

    suspend fun recordOperation(
        factId: String?,
        ownerId: MemoryOwnerId,
        type: MemoryOperationType,
        reason: String,
    ) = Unit

    suspend fun recordRetrieval(factIds: List<String>) = Unit

    suspend fun createTombstone(
        ownerId: MemoryOwnerId,
        scope: MemoryScope,
        canonicalKey: String?,
        subjectKey: String?,
        reason: String,
    ) = Unit

    suspend fun hasTombstone(
        ownerId: MemoryOwnerId,
        scopes: List<MemoryScope>,
        canonicalKey: String?,
        subjectKey: String?,
    ): Boolean = false
}
