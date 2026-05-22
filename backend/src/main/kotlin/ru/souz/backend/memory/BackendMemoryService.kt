package ru.souz.backend.memory

import java.time.Instant
import ru.souz.agent.memory.MemoryDocType
import ru.souz.agent.memory.MemoryFactRecord
import ru.souz.agent.memory.MemoryFactStatus
import ru.souz.agent.memory.MemoryGraphSnapshot
import ru.souz.agent.memory.MemoryScope
import ru.souz.agent.memory.MemoryScopeType

data class BackendMemoryOverview(
    val activeFactCount: Int,
    val activeProfileDocCount: Int,
    val activeFactDocCount: Int,
    val activeEpisodeDocCount: Int,
)

class BackendMemoryService(
    private val store: BackendMemoryStore,
    private val runtimeFactory: BackendUserMemoryRuntimeFactory,
    private val embeddingsFingerprint: () -> String,
) {
    suspend fun overview(
        userId: String,
        scope: MemoryScope? = null,
    ): BackendMemoryOverview {
        val facts = store.listFacts(userId = userId, scope = scope, statuses = setOf(MemoryFactStatus.ACTIVE))
        val docs = store.listEmbeddingDocs(
            userId = userId,
            scopes = scope?.let(::listOf).orEmpty(),
            fingerprint = embeddingsFingerprint(),
        )
        return BackendMemoryOverview(
            activeFactCount = facts.size,
            activeProfileDocCount = docs.count { it.docType == MemoryDocType.PROFILE },
            activeFactDocCount = docs.count { it.docType == MemoryDocType.FACT },
            activeEpisodeDocCount = docs.count { it.docType == MemoryDocType.EPISODE },
        )
    }

    suspend fun facts(
        userId: String,
        scope: MemoryScope? = null,
    ): List<MemoryFactRecord> =
        store.listFacts(userId = userId, scope = scope, statuses = setOf(MemoryFactStatus.ACTIVE))

    suspend fun graph(
        userId: String,
        scope: MemoryScope,
    ): MemoryGraphSnapshot =
        store.graphSnapshot(userId, scope)

    suspend fun forget(
        userId: String,
        factId: String,
    ): Boolean =
        runtimeFactory.create(userId, requestId = "memory-forget-$factId").forgetFact(factId, Instant.now())

    suspend fun invalidate(
        userId: String,
        factId: String,
    ): Boolean =
        runtimeFactory.create(userId, requestId = "memory-invalidate-$factId").invalidateFact(factId, Instant.now())

    fun defaultScopeFor(userId: String): MemoryScope =
        MemoryScope(MemoryScopeType.USER, userId)
}
