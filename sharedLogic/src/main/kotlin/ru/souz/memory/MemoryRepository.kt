package ru.souz.memory

interface MemoryRepository {
    suspend fun insertSourceEvent(input: NewMemorySourceEvent): String

    suspend fun insertFact(
        input: NewMemoryFact,
        evidence: List<MemoryEvidenceRef>,
    ): String

    suspend fun getFact(factId: String): MemoryFact?

    suspend fun getFactDetails(factId: String): MemoryFactDetails?

    suspend fun listFacts(filter: MemoryFactFilter): List<MemoryFact>

    suspend fun updateFact(
        factId: String,
        patch: MemoryFactPatch,
    ): MemoryFact

    suspend fun retireFact(factId: String)

    suspend fun deleteFact(factId: String)

    suspend fun findActiveFactBySlotKey(
        scope: MemoryScope,
        slotKey: String,
    ): MemoryFact?

    suspend fun replaceEmbedding(
        factId: String,
        model: String,
        embedding: FloatArray,
    )

    suspend fun listFactsMissingEmbedding(
        scopes: List<MemoryScope>,
        model: String,
        limit: Int,
    ): List<MemoryFact>

    suspend fun searchFacts(
        scopes: List<MemoryScope>,
        queryEmbedding: FloatArray,
        limit: Int,
    ): List<MemoryFactSearchHit>
}
