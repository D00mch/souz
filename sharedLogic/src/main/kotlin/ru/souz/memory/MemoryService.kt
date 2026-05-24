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
        val sourceEventId = repo.insertSourceEvent(
            NewMemorySourceEvent(
                scope = input.scope,
                sourceType = "manual",
                sourceRef = null,
                text = buildString {
                    appendLine(input.title.trim())
                    append(input.body.trim())
                }.trim(),
                metadataJson = restJsonMapper.writeValueAsString(
                    mapOf(
                        "kind" to input.kind.name,
                        "title" to input.title,
                    )
                ),
            )
        )
        return createFact(
            input = NewMemoryFact(
                scope = input.scope,
                kind = input.kind,
                title = input.title.trim(),
                body = input.body.trim(),
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
                    evidenceText = input.body.trim().takeIf(String::isNotBlank),
                )
            ),
        )
    }

    suspend fun createCapturedFact(input: CreateCapturedFactInput): MemoryFact {
        val existing = input.slotKey
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { repo.findActiveFactBySlotKey(input.scope, it) }
        existing?.let { repo.retireFact(it.id) }
        return createFact(
            input = NewMemoryFact(
                scope = input.scope,
                kind = input.kind,
                title = input.title.trim(),
                body = input.body.trim(),
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
                    evidenceText = input.evidenceText.trim(),
                )
            ),
        )
    }

    suspend fun saveTurnSourceEvent(input: MemoryCaptureInput): String =
        repo.insertSourceEvent(
            NewMemorySourceEvent(
                scope = input.primaryScope,
                sourceType = "turn",
                sourceRef = input.assistantMessageId ?: input.conversationId,
                text = buildString {
                    appendLine("User:")
                    appendLine(input.userMessage.trim())
                    appendLine()
                    appendLine("Assistant:")
                    append(input.assistantMessage.trim())
                },
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
        val updated = repo.updateFact(factId, patch)
        if (patch.affectsEmbedding()) {
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
        limit: Int = 8,
    ): MemoryBlock {
        if (query.isBlank() || scopes.isEmpty()) return MemoryBlock(emptyList(), "")
        val distinctScopes = scopes.distinct()
        val hits = repo.searchFacts(
            scopes = distinctScopes,
            model = embedder.model,
            queryEmbedding = embedder.embedQuery(query),
            limit = limit,
        ).filter { it.score > 0f }
        val facts = hits.map(MemoryFactSearchHit::fact)
        return MemoryBlock(facts = facts, rendered = MemoryBlockRenderer.render(facts))
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
