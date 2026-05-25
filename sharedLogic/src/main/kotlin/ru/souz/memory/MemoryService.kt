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
            is LLMResponse.Embeddings.Ok -> {
                val data = response.data.firstOrNull() ?: error("Memory embeddings returned empty data list")
                val emb = data.embedding
                if (emb.isEmpty()) {
                    error("Memory embeddings returned empty float list")
                }
                emb.map(Double::toFloat).toFloatArray()
            }
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
        return try {
            createFact(
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
        } catch (error: Exception) {
            runCatching { repo.deleteSourceEventIfUnused(sourceEventId) }
            throw error
        }
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
        val existing = repo.getFact(factId) ?: error("Memory fact not found: $factId")
        val updated = existing.applyPatch(normalizedPatch)
        val embedding = if (normalizedPatch.affectsEmbedding()) {
            embedder.embedDocument(updated.embeddingText())
        } else {
            null
        }
        return repo.updateFact(
            fact = updated,
            expectedUpdatedAt = existing.updatedAt,
            embedding = embedding,
            embeddingModel = embedding?.let { embedder.model },
        )
    }

    suspend fun retireFact(factId: String) {
        repo.retireFact(factId)
    }

    suspend fun deleteFact(factId: String) {
        repo.deleteFact(factId)
    }

    suspend fun deleteSourceEventIfUnused(sourceEventId: String) {
        repo.deleteSourceEventIfUnused(sourceEventId)
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
        val embeddingText = buildString {
            appendLine(input.title)
            appendLine(input.body)
            appendLine("kind=${input.kind}")
            appendLine("scope=${input.scope.type}:${input.scope.id}")
        }
        val emb = embedder.embedDocument(embeddingText)
        val factId = repo.insertFact(
            input = input,
            evidence = evidence,
            embedding = emb,
            embeddingModel = embedder.model,
        )
        return repo.getFact(factId) ?: error("Memory fact not found after insert: $factId")
    }

    private fun MemoryFact.embeddingText(): String = buildString {
        appendLine(title)
        appendLine(body)
        appendLine("kind=$kind")
        appendLine("scope=${scope.type}:${scope.id}")
    }

    private fun MemoryFact.applyPatch(patch: MemoryFactPatch): MemoryFact {
        val nextSlotKey = when {
            patch.clearSlotKey -> null
            patch.slotKey != null -> patch.slotKey.trim().ifBlank { null }
            else -> slotKey
        }
        return copy(
            scope = patch.scope ?: scope,
            kind = patch.kind ?: kind,
            title = patch.title?.trim()?.ifBlank { title } ?: title,
            body = patch.body?.trim()?.ifBlank { body } ?: body,
            slotKey = nextSlotKey,
            confidence = patch.confidence ?: confidence,
            pinned = patch.pinned ?: pinned,
            updatedAt = java.time.Instant.now(),
        )
    }

    private fun MemoryFactPatch.affectsEmbedding(): Boolean =
        scope != null || kind != null || title != null || body != null
}
