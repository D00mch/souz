package ru.souz.memory

import ru.souz.llms.EmbeddingInputKind
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.db.SettingsProvider
import ru.souz.llms.restJsonMapper

interface EmbeddingClient {
    val model: String

    suspend fun embedQuery(text: String): FloatArray

    suspend fun embedDocument(text: String): FloatArray
}

class LlmEmbeddingClient(
    private val api: LLMChatAPI,
    private val settingsProvider: SettingsProvider,
) : EmbeddingClient {
    override val model: String
        get() = settingsProvider.embeddingsModel.alias

    override suspend fun embedQuery(text: String): FloatArray = embed(text, EmbeddingInputKind.QUERY)

    override suspend fun embedDocument(text: String): FloatArray = embed(text, EmbeddingInputKind.DOCUMENT)

    private suspend fun embed(
        text: String,
        inputKind: EmbeddingInputKind,
    ): FloatArray {
        val response = api.embeddings(
            LLMRequest.Embeddings(
                model = model,
                input = listOf(text),
                inputKind = inputKind,
            )
        )
        return when (response) {
            is LLMResponse.Embeddings.Ok -> response.data.firstOrNull()
                ?.embedding
                ?.map(Double::toFloat)
                ?.toFloatArray()
                ?: FloatArray(0)
            is LLMResponse.Embeddings.Error -> error("Memory embeddings failed: ${response.status} ${response.message}")
        }
    }
}

class MemoryService(
    private val repo: MemoryRepository,
    private val embedder: EmbeddingClient,
) {
    suspend fun createManualFact(input: CreateMemoryFactInput): MemoryFact {
        val scope = input.scope.normalized()
        val cleanTitle = MemorySanitizer.redact(input.title.trim())
        val cleanBody = MemorySanitizer.redact(input.body.trim())
        val sourceEventId = repo.insertSourceEvent(
            NewMemorySourceEvent(
                scope = scope,
                sourceType = "manual",
                sourceRef = null,
                text = buildString {
                    appendLine(cleanTitle)
                    append(cleanBody)
                }.trim(),
                metadataJson = restJsonMapper.writeValueAsString(
                    mapOf(
                        "kind" to input.kind.name,
                        "title" to cleanTitle,
                    )
                ),
            )
        )
        return createFact(
            input = NewMemoryFact(
                scope = scope,
                kind = input.kind,
                title = cleanTitle,
                body = cleanBody,
                slotKey = input.slotKey?.trim()?.ifBlank { null },
                status = MemoryFactStatus.ACTIVE,
                confidence = input.confidence,
                pinned = input.pinned,
                createdBy = "user",
                supersedesFactId = null,
            ),
            evidence = listOf(
                MemoryEvidenceRef(
                    sourceEventId = sourceEventId,
                    evidenceText = cleanBody.takeIf(String::isNotBlank),
                )
            ),
        )
    }

    suspend fun createCapturedFact(input: CreateCapturedFactInput): MemoryFact {
        val scope = input.scope.normalized()
        val cleanTitle = MemorySanitizer.redact(input.title.trim())
        val cleanBody = MemorySanitizer.redact(input.body.trim())
        val cleanEvidence = MemorySanitizer.redact(input.evidenceText.trim())
        val existing = input.slotKey
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { repo.findActiveFactBySlotKey(scope, it) }
        existing?.let { repo.retireFact(it.id) }
        return createFact(
            input = NewMemoryFact(
                scope = scope,
                kind = input.kind,
                title = cleanTitle,
                body = cleanBody,
                slotKey = input.slotKey?.trim()?.ifBlank { null },
                status = MemoryFactStatus.ACTIVE,
                confidence = input.confidence,
                pinned = input.pinned,
                createdBy = "writer",
                supersedesFactId = existing?.id,
            ),
            evidence = listOf(
                MemoryEvidenceRef(
                    sourceEventId = input.sourceEventId,
                    evidenceText = cleanEvidence,
                )
            ),
        )
    }

    suspend fun saveRedactedSourceEvent(input: MemoryCaptureInput, redactedText: String): String =
        repo.insertSourceEvent(
            NewMemorySourceEvent(
                scope = input.primaryScope.normalized(),
                sourceType = "turn",
                sourceRef = input.assistantMessageId ?: input.conversationId,
                text = redactedText,
                metadataJson = restJsonMapper.writeValueAsString(
                    mapOf(
                        "conversationId" to input.conversationId,
                        "userMessageId" to input.userMessageId,
                        "assistantMessageId" to input.assistantMessageId,
                    )
                ),
            )
        )

    suspend fun listFacts(filter: MemoryFactFilter): List<MemoryFact> = repo.listFacts(filter)

    suspend fun getFactDetails(factId: String): MemoryFactDetails? = repo.getFactDetails(factId)

    suspend fun updateFact(
        factId: String,
        patch: MemoryFactPatch,
    ): MemoryFact {
        val normalizedPatch = patch.copy(scope = patch.scope?.normalized())
        val updated = repo.updateFact(factId, normalizedPatch)
        if (normalizedPatch.affectsEmbedding()) {
            repo.replaceEmbedding(
                factId = updated.id,
                model = embedder.model,
                embedding = embedder.embedDocument(updated.embeddingText()),
            )
        }
        return updated
    }

    suspend fun retireFact(factId: String) {
        repo.retireFact(factId)
    }

    suspend fun deleteFact(factId: String) {
        repo.deleteFact(factId)
    }

    suspend fun retrieveForPrompt(
        scopes: List<MemoryScope>,
        query: String,
        limit: Int = 5,
    ): MemoryBlock {
        if (scopes.isEmpty()) return MemoryBlock(emptyList(), "", emptyList())
        val distinctScopes = scopes
            .flatMap(MemoryScope::compatibilityScopes)
            .distinct()

        runCatching {
            val missing = repo.getFactsWithoutEmbedding(
                scopes = distinctScopes,
                model = embedder.model,
            )
            for (fact in missing) {
                runCatching {
                    val emb = embedder.embedDocument(fact.embeddingText())
                    repo.replaceEmbedding(fact.id, embedder.model, emb)
                }
            }
        }

        val pinnedFacts = distinctScopes
            .flatMap { scope -> repo.listFacts(MemoryFactFilter(scope = scope, limit = Int.MAX_VALUE)) }
            .filter(MemoryFact::pinned)
            .distinctBy(MemoryFact::id)
            .sortedByDescending(MemoryFact::updatedAt)
            .take(limit)
        val pinnedIds = pinnedFacts.mapTo(linkedSetOf(), MemoryFact::id)
        val semanticHits = if (query.isBlank()) {
            emptyList()
        } else {
            repo.searchFacts(
                scopes = distinctScopes,
                model = embedder.model,
                queryEmbedding = embedder.embedQuery(query),
                limit = limit + pinnedFacts.size,
            ).filter { it.score > 0f && it.fact.id !in pinnedIds }
        }
        val hits = buildList {
            pinnedFacts.forEach { fact -> add(MemoryFactSearchHit(fact, 1f)) }
            addAll(semanticHits)
        }.take(limit)
        val facts = hits.map(MemoryFactSearchHit::fact)
        return MemoryBlock(facts = facts, rendered = renderMemoryPrompt(hits), hits = hits)
    }

    private suspend fun createFact(
        input: NewMemoryFact,
        evidence: List<MemoryEvidenceRef>,
    ): MemoryFact {
        val factId = repo.insertFact(input, evidence)
        val fact = repo.getFact(factId) ?: error("Memory fact not found after insert: $factId")
        repo.replaceEmbedding(
            factId = factId,
            model = embedder.model,
            embedding = embedder.embedDocument(fact.embeddingText()),
        )
        return fact
    }

    private fun MemoryFact.embeddingText(): String = buildString {
        appendLine(title)
        appendLine(body)
        appendLine("kind=$kind")
        appendLine("scope=${scope.type}:${scope.id}")
    }

    private fun MemoryFactPatch.affectsEmbedding(): Boolean =
        scope != null || kind != null || title != null || body != null
}
