package ru.souz.memory

import kotlinx.coroutines.CancellationException
import ru.souz.llms.EmbeddingInputKind
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.db.SettingsProvider
import ru.souz.llms.restJsonMapper
import kotlin.math.roundToInt

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
                ownerId = input.ownerId,
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
        return withSourceEventCleanup(sourceEventId) {
            createFact(
                input = NewMemoryFact(
                    ownerId = input.ownerId,
                    scope = scope,
                    kind = input.kind,
                    title = cleanTitle,
                    body = cleanBody,
                    canonicalKey = controlledCanonicalKey(input.canonicalKey),
                    status = MemoryFactStatus.ACTIVE,
                    retention = retentionForScope(scope),
                    confidence = input.confidence,
                    importance = input.importance,
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
    }

    suspend fun createCapturedFact(input: CreateCapturedFactInput): MemoryFact {
        return tryCreateCapturedFact(input) ?: error("Memory fact is blocked by tombstone")
    }

    suspend fun tryCreateCapturedFact(input: CreateCapturedFactInput): MemoryFact? {
        val scope = input.scope.normalized()
        val cleanTitle = MemorySanitizer.redact(input.title.trim())
        val cleanBody = MemorySanitizer.redact(input.body.trim())
        val cleanEvidence = MemorySanitizer.redact(input.evidenceText.trim())
        val canonicalKey = controlledCanonicalKey(input.canonicalKey)
        if (isScopeClosedForCapture(input.ownerId, scope)) return null
        val existing = canonicalKey?.let { repo.findActiveFactByCanonicalKey(input.ownerId, scope, it) }
        if (repo.hasTombstone(input.ownerId, listOf(scope), canonicalKey, cleanTitle.normalizedSubjectKey())) {
            return null
        }
        return try {
            createFact(
                input = NewMemoryFact(
                    ownerId = input.ownerId,
                    scope = scope,
                    kind = input.kind,
                    title = cleanTitle,
                    body = cleanBody,
                    canonicalKey = canonicalKey,
                    status = MemoryFactStatus.ACTIVE,
                    retention = retentionForScope(scope),
                    confidence = input.confidence,
                    importance = input.importance,
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
        } catch (_: MemoryScopeClosedForCaptureException) {
            null
        }
    }

    suspend fun saveRedactedSourceEvent(input: MemoryCaptureInput, redactedText: String): String =
        repo.insertSourceEvent(
            NewMemorySourceEvent(
                ownerId = input.context.ownerId,
                scope = input.context.sessionId?.let { MemoryScope.session(it) }
                    ?: input.context.projectId?.let { MemoryScope.project(it) }
                    ?: globalMemoryScope(),
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
        val saved = repo.updateFact(
            fact = updated,
            expectedUpdatedAt = existing.updatedAt,
        )
        if (normalizedPatch.affectsEmbedding()) {
            enqueueAndTryEmbedding(saved)
        }
        repo.recordOperation(saved.id, saved.ownerId, MemoryOperationType.UPDATE, "manual_update")
        return saved
    }

    suspend fun retireFact(factId: String) = repo.retireFact(factId)

    suspend fun deleteFact(factId: String) = repo.deleteFact(factId)

    suspend fun deleteFactsByScope(ownerId: MemoryOwnerId, scope: MemoryScope) =
        repo.deleteFactsByScope(ownerId, scope)

    suspend fun closeScopeForCapture(ownerId: MemoryOwnerId, scope: MemoryScope) =
        repo.createTombstone(
            ownerId = ownerId,
            scope = scope.normalized(),
            canonicalKey = null,
            subjectKey = MEMORY_SCOPE_CLOSED_SUBJECT_KEY,
            reason = "scope_closed",
        )

    suspend fun isScopeClosedForCapture(ownerId: MemoryOwnerId, scope: MemoryScope): Boolean =
        repo.hasTombstone(
            ownerId = ownerId,
            scopes = listOf(scope.normalized()),
            canonicalKey = null,
            subjectKey = MEMORY_SCOPE_CLOSED_SUBJECT_KEY,
        )

    suspend fun deleteSourceEventIfUnused(sourceEventId: String) = repo.deleteSourceEventIfUnused(sourceEventId)

    suspend fun retrieveForPrompt(
        scopes: List<MemoryScope>,
        query: String,
        limit: Int = 5,
    ): MemoryBlock {
        val context = legacyMemoryContext()
        val selected = selectMemoryFacts(
            context = context,
            query = query,
            maxFacts = limit,
            maxPromptTokens = 700,
            overrideScopes = scopes,
        ).selected
        val hits = selected.map { MemoryFactSearchHit(it.fact, it.score) }
        val rendered = renderMemoryPrompt(hits)
        return MemoryBlock(
            facts = hits.map(MemoryFactSearchHit::fact),
            rendered = rendered,
            hits = hits,
        )
    }

    suspend fun retrieveMemory(
        request: MemoryRetrievalRequest,
        overrideScopes: List<MemoryScope>? = null,
    ): MemoryRetrievalResult {
        val selection = selectMemoryFacts(
            context = request.context,
            query = request.query,
            maxFacts = request.maxFacts ?: 8,
            maxPromptTokens = request.maxPromptTokens ?: 700,
            overrideScopes = overrideScopes,
        )
        val hits = selection.selected.map { MemoryFactSearchHit(it.fact, it.score) }
        val rendered = renderMemoryPrompt(hits)
        return MemoryRetrievalResult(
            renderedPromptBlock = rendered.ifBlank { null },
            facts = selection.selected.map {
                MemoryPromptFact(
                    factId = it.fact.id,
                    scope = "${it.fact.scope.type}:${it.fact.scope.id}",
                    score = it.score,
                )
            },
            trace = selection.trace.copy(
                promptTokenEstimate = estimatePromptTokens(rendered),
            ),
        )
    }

    private data class MemorySelection(
        val selected: List<RetrievedMemoryFact>,
        val trace: MemoryRetrievalTrace,
    )

    private suspend fun selectMemoryFacts(
        context: MemoryContext,
        query: String,
        maxFacts: Int,
        maxPromptTokens: Int,
        overrideScopes: List<MemoryScope>? = null,
    ): MemorySelection {
        val scopes = (overrideScopes ?: context.allowedRetrievalScopes())
            .flatMap(MemoryScope::compatibilityScopes)
            .distinct()
        val limit = maxFacts.coerceIn(1, 32)
        val tokenBudget = maxPromptTokens.coerceAtLeast(80)
        if (scopes.isEmpty()) return MemorySelection(emptyList(), MemoryRetrievalTrace())
        val trimmedQuery = query.trim()
        val exact = exactCandidates(context.ownerId, scopes, trimmedQuery, 20)
        val lexical = try {
            repo.lexicalSearchFacts(context.ownerId, scopes, trimmedQuery, 40)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
        val dense = denseCandidates(context.ownerId, scopes, trimmedQuery, 40)
        val priority = priorityCandidates(context.ownerId, scopes, trimmedQuery, 20)

        val fused = weightedRrf(
            "exact" to (3.0 to exact),
            "lexical" to (1.4 to lexical),
            "dense" to (1.0 to dense),
            "priority" to (0.7 to priority),
        )
        val selected = fused
            .asSequence()
            .filter { it.fact.status == MemoryFactStatus.ACTIVE && it.fact.validity != MemoryFactValidity.CONTRADICTED }
            .filter { it.fact.retention != MemoryRetention.SESSION_LIFETIME || it.fact.scope in scopes.map { scope -> scope.normalized() } }
            .sortedWith(compareByDescending<FusedCandidate> { heuristicScore(it, context) }.thenByDescending { it.fact.updatedAt })
            .distinctBy { it.fact.canonicalKey ?: it.fact.contentHash }
            .takeForPrompt(limit, tokenBudget)
            .map { RetrievedMemoryFact(it.fact, heuristicScore(it, context).toFloat(), it.sources) }
            .toList()
        repo.recordRetrieval(selected.map { it.fact.id })
        return MemorySelection(
            selected = selected,
            trace = MemoryRetrievalTrace(
                candidateCountBySource = mapOf(
                    "exact" to exact.size,
                    "lexical" to lexical.size,
                    "dense" to dense.size,
                    "priority" to priority.size,
                ),
                selectedFactIds = selected.map { it.fact.id },
            ),
        )
    }

    private suspend fun createFact(
        input: NewMemoryFact,
        evidence: List<MemoryEvidenceRef>,
    ): MemoryFact {
        val factId = repo.insertFact(
            input = input,
            evidence = evidence,
        )
        val fact = repo.getFact(factId) ?: error("Memory fact not found after insert: $factId")
        repo.recordOperation(fact.id, fact.ownerId, MemoryOperationType.CREATE, fact.createdBy)
        enqueueAndTryEmbedding(fact)
        return repo.getFact(factId) ?: fact
    }

    @Suppress("DEPRECATION")
    private fun MemoryFact.applyPatch(patch: MemoryFactPatch): MemoryFact {
        val nextCanonicalKey = when {
            patch.clearCanonicalKey || patch.clearSlotKey -> null
            patch.canonicalKey != null -> controlledCanonicalKey(patch.canonicalKey)
            patch.slotKey != null -> controlledCanonicalKey(patch.slotKey)
            else -> canonicalKey
        }
        val nextTitle = patch.title?.trim()?.ifBlank { title } ?: title
        val nextBody = patch.body?.trim()?.ifBlank { body } ?: body
        val nextKind = patch.kind ?: kind
        val nextScope = patch.scope ?: scope
        return copy(
            scope = nextScope,
            kind = nextKind,
            title = nextTitle,
            body = nextBody,
            canonicalKey = nextCanonicalKey,
            retention = if (patch.scope != null) retentionForScope(nextScope) else retention,
            confidence = patch.confidence ?: confidence,
            importance = patch.importance ?: importance,
            pinned = patch.pinned ?: pinned,
            version = version + 1,
            contentHash = stableMemoryContentHash(nextTitle, nextBody, nextKind, nextCanonicalKey),
            updatedAt = java.time.Instant.now(),
        )
    }

    @Suppress("DEPRECATION")
    private fun MemoryFactPatch.affectsEmbedding(): Boolean =
        scope != null || kind != null || title != null || body != null || canonicalKey != null || slotKey != null || clearCanonicalKey || clearSlotKey

    private fun controlledCanonicalKey(raw: String?): String? =
        normalizeCanonicalKey(raw)

    private suspend fun enqueueAndTryEmbedding(fact: MemoryFact) {
        repo.enqueueEmbeddingJob(fact.id, fact.ownerId, embedder.model, fact.contentHash)
        try {
            repo.replaceEmbedding(
                factId = fact.id,
                model = embedder.model,
                embedding = embedder.embedDocument(fact.embeddingText()),
                contentHash = fact.contentHash,
            )
            repo.markEmbeddingJobCompleted(fact.id, embedder.model, fact.contentHash)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            repo.markEmbeddingJobFailed(fact.id, embedder.model, fact.contentHash, e::class.simpleName ?: "embedding_error")
        }
    }

    suspend fun forgetFromText(
        context: MemoryContext,
        text: String,
        hardDelete: Boolean = false,
    ): Int {
        val scopes = context.allowedRetrievalScopes(includeChat = context.surface == MemorySurface.BACKEND)
        val query = text.removeForgetMarkers()
        val match = confidentForgetMatch(context.ownerId, scopes, query) ?: return 0
        if (hardDelete) {
            repo.deleteFact(match.id)
            repo.recordOperation(match.id, match.ownerId, MemoryOperationType.DELETE, "explicit_delete")
        } else {
            repo.retireFact(match.id)
            repo.recordOperation(match.id, match.ownerId, MemoryOperationType.FORGET, "explicit_forget")
            repo.createTombstone(
                ownerId = match.ownerId,
                scope = match.scope,
                canonicalKey = match.canonicalKey,
                subjectKey = match.title.normalizedSubjectKey(),
                reason = "explicit_forget",
            )
        }
        return 1
    }

    private suspend fun confidentForgetMatch(
        ownerId: MemoryOwnerId,
        scopes: List<MemoryScope>,
        query: String,
    ): MemoryFact? {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return null

        normalizeCanonicalKey(normalizedQuery)?.let { key ->
            val matches = scopes
                .mapNotNull { scope -> repo.findActiveFactByCanonicalKey(ownerId, scope, key) }
                .distinctBy(MemoryFact::id)
            return matches.singleOrNull()
        }

        val lowerQuery = normalizedQuery.lowercase()
        val matches = scopes
            .flatMap { scope ->
                repo.listFacts(
                    MemoryFactFilter(
                        ownerId = ownerId,
                        scope = scope,
                        statuses = setOf(MemoryFactStatus.ACTIVE),
                        limit = 100,
                    )
                )
            }
            .filter { fact ->
                fact.title.trim().lowercase() == lowerQuery ||
                    fact.canonicalKey?.trim()?.lowercase() == lowerQuery
            }
            .distinctBy(MemoryFact::id)
        return matches.singleOrNull()
    }

    private suspend fun exactCandidates(
        ownerId: MemoryOwnerId,
        scopes: List<MemoryScope>,
        query: String,
        limit: Int,
    ): List<MemoryFactSearchHit> {
        val key = normalizeCanonicalKey(query)
        val byKey = if (key != null) {
            scopes.mapNotNull { repo.findActiveFactByCanonicalKey(ownerId, it, key) }
        } else {
            emptyList()
        }
        val identifiers = query.normalizedTerms().filter { it.length >= 3 }.take(8)
        val byText = scopes
            .flatMap { repo.listFacts(MemoryFactFilter(ownerId = ownerId, scope = it, limit = 100)) }
            .filter { fact ->
                identifiers.any { term ->
                    fact.title.lowercase().contains(term) ||
                        fact.body.lowercase().contains(term) ||
                        fact.canonicalKey.orEmpty().lowercase().contains(term)
                }
            }
        return (byKey + byText)
            .distinctBy(MemoryFact::id)
            .take(limit)
            .mapIndexed { index, fact -> MemoryFactSearchHit(fact, 1f - index * 0.01f) }
    }

    private suspend fun denseCandidates(
        ownerId: MemoryOwnerId,
        scopes: List<MemoryScope>,
        query: String,
        limit: Int,
    ): List<MemoryFactSearchHit> {
        if (query.isBlank()) return emptyList()
        val queryEmbedding = try {
            embedder.embedQuery(query)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return emptyList()
        }
        return repo.searchFacts(
            ownerId = ownerId,
            scopes = scopes,
            model = embedder.model,
            queryEmbedding = queryEmbedding,
            limit = limit,
        ).filter { it.score >= 0.15f }
    }

    private suspend fun priorityCandidates(
        ownerId: MemoryOwnerId,
        scopes: List<MemoryScope>,
        query: String,
        limit: Int,
    ): List<MemoryFactSearchHit> {
        val terms = query.normalizedTerms().toSet()
        return scopes
            .flatMap { scope ->
                repo.listFacts(MemoryFactFilter(ownerId = ownerId, scope = scope, pinned = true, limit = limit))
            }
            .distinctBy(MemoryFact::id)
            .mapNotNull { fact ->
                val relevance = lexicalOverlap(terms, fact)
                if (query.isBlank() || relevance > 0f) MemoryFactSearchHit(fact, 0.65f + relevance) else null
            }
            .sortedByDescending(MemoryFactSearchHit::score)
            .take(limit)
    }

    private data class FusedCandidate(
        val fact: MemoryFact,
        val score: Double,
        val sources: Set<String>,
    )

    private fun weightedRrf(vararg sources: Pair<String, Pair<Double, List<MemoryFactSearchHit>>>): List<FusedCandidate> {
        val scores = linkedMapOf<String, Pair<MemoryFact, Double>>()
        val sourceNames = linkedMapOf<String, MutableSet<String>>()
        sources.forEach { (sourceName, weightedHits) ->
            val (weight, hits) = weightedHits
            hits.distinctBy { it.fact.id }.forEachIndexed { index, hit ->
                val rank = index + 1
                val increment = weight / (60.0 + rank)
                val current = scores[hit.fact.id]?.second ?: 0.0
                scores[hit.fact.id] = hit.fact to current + increment
                sourceNames.getOrPut(hit.fact.id) { linkedSetOf() }.add(sourceName)
            }
        }
        return scores.values
            .map { (fact, score) -> FusedCandidate(fact, score, sourceNames.getValue(fact.id)) }
            .sortedByDescending(FusedCandidate::score)
    }

    private fun heuristicScore(candidate: FusedCandidate, context: MemoryContext): Double {
        val fact = candidate.fact
        var score = candidate.score
        score += fact.confidence.coerceIn(0f, 1f) * 0.04
        score += fact.importance.coerceIn(0f, 1f) * 0.05
        if (fact.pinned) score += 0.03
        if (fact.scope.type == "project" && context.projectId?.value == fact.scope.id) score += 0.06
        if (fact.scope.type == "session" && context.sessionId?.value == fact.scope.id) score += 0.04
        if (fact.validity == MemoryFactValidity.UNCERTAIN) score -= 0.08
        return score
    }

    private fun Sequence<FusedCandidate>.takeForPrompt(limit: Int, tokenBudget: Int): Sequence<FusedCandidate> = sequence {
        var used = 0
        var count = 0
        for (candidate in this@takeForPrompt) {
            val tokens = estimatePromptTokens("${candidate.fact.title}: ${candidate.fact.body}")
            if (count >= limit) break
            if (used + tokens > tokenBudget && count > 0) continue
            used += tokens
            count++
            yield(candidate)
        }
    }

    private fun estimatePromptTokens(text: String): Int = (text.length / 4.0).roundToInt().coerceAtLeast(1)

    private fun String.normalizedTerms(): List<String> =
        lowercase().split(Regex("""[^\p{L}\p{N}_./:-]+""")).filter { it.isNotBlank() }

    private fun lexicalOverlap(terms: Set<String>, fact: MemoryFact): Float {
        if (terms.isEmpty()) return 0f
        val factText = "${fact.title} ${fact.body} ${fact.canonicalKey.orEmpty()}".lowercase()
        return terms.count { factText.contains(it) }.toFloat() / terms.size
    }

    private fun String.normalizedSubjectKey(): String? =
        lowercase().normalizedTerms().filter { it.length >= 3 }.take(6).joinToString(".").ifBlank { null }

    private fun String.removeForgetMarkers(): String =
        replace(Regex("""(?i)\b(forget|delete from memory|delete this memory|remember no longer)\b"""), " ")
            .replace(Regex("""(?i)\b(забудь|удали из памяти|полностью удали|больше не считай)\b"""), " ")
            .trim()

    private suspend fun <T> withSourceEventCleanup(sourceEventId: String, block: suspend () -> T): T = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        try {
            repo.deleteSourceEventIfUnused(sourceEventId)
        } catch (cleanupCancellation: CancellationException) {
            throw cleanupCancellation
        } catch (_: Exception) {
            // Best-effort cleanup; keep the original write failure visible.
        }
        throw error
    }

    private fun NewMemoryFact.embeddingText(): String = embeddingText(title, body, kind, scope)

    private fun MemoryFact.embeddingText(): String = embeddingText(title, body, kind, scope)

    private fun embeddingText(
        title: String,
        body: String,
        kind: MemoryFactKind,
        scope: MemoryScope,
    ): String = buildString {
        appendLine(title)
        appendLine(body)
        appendLine("kind=$kind")
        appendLine("scope=${scope.type}:${scope.id}")
    }
}
