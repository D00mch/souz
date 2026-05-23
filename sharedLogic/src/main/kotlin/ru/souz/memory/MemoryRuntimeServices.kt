package ru.souz.memory

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Path
import java.time.Instant
import java.util.LinkedHashMap
import org.slf4j.LoggerFactory
import ru.souz.agent.memory.DefaultMemoryCandidateValidator
import ru.souz.agent.memory.MemoryCandidate
import ru.souz.agent.memory.MemoryCandidateValidationInput
import ru.souz.agent.memory.MemoryConflictPolicy
import ru.souz.agent.memory.MemoryDocType
import ru.souz.agent.memory.MemoryEmbeddingDocRecord
import ru.souz.agent.memory.MemoryEpisodeRecord
import ru.souz.agent.memory.MemoryEvidenceRecord
import ru.souz.agent.memory.MemoryEvidenceType
import ru.souz.agent.memory.MemoryFactRecord
import ru.souz.agent.memory.MemoryFactSnapshot
import ru.souz.agent.memory.MemoryFactStatus
import ru.souz.agent.memory.MemoryGraphSnapshot
import ru.souz.agent.memory.MemoryInjectionRequest
import ru.souz.agent.memory.MemoryInjectionResult
import ru.souz.agent.memory.MemoryObjectKind
import ru.souz.agent.memory.MemoryPacket
import ru.souz.agent.memory.MemoryRuntimeServicesContract
import ru.souz.agent.memory.MemoryScope
import ru.souz.agent.memory.MemoryScopeType
import ru.souz.agent.memory.MemoryWriteInput
import ru.souz.agent.memory.MemoryWriteResult
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse

class MemoryRuntimeServices(
    private val store: MemoryCanonicalStore,
    private val embeddingsApi: LLMChatAPI,
    private val embeddingsFingerprint: () -> String,
    private val userScope: MemoryScope,
    vectorIndexDir: Path,
    private val chatModelAlias: () -> String = { LLMModel.Max.alias },
    private val workspaceScope: MemoryScope? = null,
    private val projectScope: MemoryScope? = null,
) : MemoryRuntimeServicesContract {
    private val logger = LoggerFactory.getLogger(MemoryRuntimeServices::class.java)
    private val validator = DefaultMemoryCandidateValidator()
    private val mapper = jacksonObjectMapper()
        .findAndRegisterModules()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    private val vectorIndex = MemoryVectorIndex(vectorIndexDir)

    override suspend fun write(input: MemoryWriteInput): MemoryWriteResult {
        val evidenceIndex = buildEvidenceBundle(input)
        val activeFacts = store.listFacts(scope = null, statuses = setOf(MemoryFactStatus.ACTIVE))
        val snapshots = toSnapshots(activeFacts)
        val extraction = extractCandidates(input = input, evidenceIndex = evidenceIndex)
        val candidates = extraction.candidates
        val acceptedFacts = mutableListOf<MemoryFactRecord>()
        val candidateAudits = mutableListOf<LoggedCandidate>()
        val rejectionReasons = extraction.rejectionReasons.toMutableList()
        val allowedScopes = allowedWriteScopes(input.scope).toSet()

        candidates.forEach { candidate ->
            val validation = validator.validate(
                MemoryCandidateValidationInput(
                    candidate = candidate,
                    evidenceIndex = evidenceIndex,
                    activeFacts = snapshots.filter { it.scope == candidate.scope },
                    acceptedCountThisTurn = acceptedFacts.size,
                    allowedScopes = allowedScopes,
                )
            )
            if (!validation.accepted) {
                val rejectionReason = validation.rejectionReason?.name.orEmpty()
                rejectionReasons += rejectionReason
                candidateAudits += LoggedCandidate(
                    candidate = candidate,
                    accepted = false,
                    rejectionReason = rejectionReason.ifBlank { null },
                )
                return@forEach
            }

            val subject = store.resolveOrUpsertEntity(
                ru.souz.agent.memory.MemoryEntityRecord(
                    scope = candidate.scope,
                    entityType = candidate.subjectEntityType,
                    canonicalName = candidate.subjectCanonicalName,
                    displayName = candidate.subjectDisplayName,
                    normalizedKey = candidate.subjectNormalizedKey,
                )
            )
            val objectEntityId = if (candidate.objectKind == MemoryObjectKind.ENTITY) {
                store.resolveOrUpsertEntity(
                    ru.souz.agent.memory.MemoryEntityRecord(
                        scope = candidate.scope,
                        entityType = candidate.objectEntityType.orEmpty(),
                        canonicalName = candidate.objectEntityCanonicalName.orEmpty(),
                        displayName = candidate.objectEntityDisplayName ?: candidate.objectEntityCanonicalName.orEmpty(),
                        normalizedKey = candidate.objectEntityNormalizedKey.orEmpty(),
                    )
                ).id
            } else {
                null
            }
            acceptedFacts += store.insertFact(
                MemoryFactRecord(
                    scope = candidate.scope,
                    subjectEntityId = subject.id!!,
                    predicate = candidate.predicate,
                    objectKind = candidate.objectKind,
                    objectEntityId = objectEntityId,
                    objectValueText = candidate.objectValueText,
                    objectValueJson = candidate.objectValueJson,
                    slotKey = candidate.slotKey,
                    confidence = candidate.confidence,
                    status = candidate.suggestedStatus,
                    reasonToStore = candidate.reasonToStore,
                ),
                evidenceIds = candidate.evidenceRefs.mapNotNull(evidenceIndex::get).mapNotNull { it.id },
            )
            candidateAudits += LoggedCandidate(candidate = candidate, accepted = true)
        }

        val attemptId = store.logWriteAttempt(
            scope = input.scope,
            turnRef = input.turnRef,
            triggerType = input.triggerType,
            inputExcerpt = input.userMessage?.take(MAX_EXCERPT_LENGTH) ?: input.assistantMessage?.take(MAX_EXCERPT_LENGTH),
            candidatesJson = mapper.writeValueAsString(
                StoredExtractionAuditEnvelope(
                    audits = candidateAudits,
                    rawOutput = extraction.rawOutput,
                    rawOutputKind = extraction.rawOutputKind,
                    emptyReason = extraction.emptyReason,
                )
            ),
            acceptedCount = acceptedFacts.size,
            rejectedCount = rejectionReasons.size,
            rejectionReasonsJson = rejectionReasons.takeIf { it.isNotEmpty() }?.let(mapper::writeValueAsString),
            createdAt = Instant.now(),
        )
        if (acceptedFacts.isNotEmpty()) {
            rebuildProjection()
        }
        return MemoryWriteResult(
            acceptedFacts = acceptedFacts,
            rejectedCount = rejectionReasons.size,
            writeAttemptId = attemptId,
        )
    }

    override suspend fun inject(request: MemoryInjectionRequest): MemoryInjectionResult {
        return try {
            val scopes = relevantScopes(request.scope)
            val currentFingerprint = embeddingsFingerprint()
            val allDocs = store.listEmbeddingDocs(scopes = scopes, fingerprint = currentFingerprint)
            if (allDocs.isEmpty()) {
                return MemoryInjectionResult()
            }
            val selected = LinkedHashMap<String, MemoryEmbeddingDocRecord>()
            allDocs
                .filter { it.docType == MemoryDocType.PROFILE }
                .forEach { selected[it.id ?: it.sourceRecordId] = it }
            allDocs
                .filter { it.docType == MemoryDocType.EPISODE && it.scope == request.scope }
                .forEach { selected[it.id ?: it.sourceRecordId] = it }
            vectorIndex.search(
                query = request.queryText,
                embeddingsApi = embeddingsApi,
                fingerprint = currentFingerprint,
                limit = request.maxItems.coerceAtLeast(1),
            ).forEach { hit ->
                val docId = hit.id ?: return@forEach
                val doc = allDocs.firstOrNull { it.id == docId } ?: return@forEach
                if (doc.scope !in scopes) return@forEach
                selected.putIfAbsent(docId, doc)
            }

            val packets = collapseFactDocsBySlot(
                docs = selected.values.toList(),
                scopes = scopes,
            ).take(request.maxItems.coerceAtLeast(0))
                .map { doc ->
                    MemoryPacket(
                        recordId = doc.sourceRecordId,
                        text = "- ${doc.text}",
                    )
                }
            val renderedBlock = packets.joinToString(separator = "\n") { it.text }
            val result = MemoryInjectionResult(
                packets = packets,
                renderedBlock = renderedBlock,
                selectedRecordIds = packets.map { it.recordId },
                estimatedTokens = localEstimateTokenCount(renderedBlock),
                debugSummary = "items=${packets.size}",
            )
            store.logInjection(
                scope = request.scope,
                turnRef = request.turnRef,
                queryExcerpt = request.queryText.take(MAX_EXCERPT_LENGTH),
                selectedRecordIds = result.selectedRecordIds,
                renderedPacket = renderedBlock,
                estimatedTokens = result.estimatedTokens,
                createdAt = Instant.now(),
            )
            result
        } catch (t: Throwable) {
            logger.warn("Memory retrieval degraded to no-memory: {}", t.message)
            MemoryInjectionResult()
        }
    }

    override suspend fun graphSnapshot(scope: MemoryScope): MemoryGraphSnapshot =
        store.graphSnapshot(scope)

    override suspend fun forgetFact(factId: String, at: Instant): Boolean {
        val updated = store.forgetFact(factId, at)
        if (updated) {
            rebuildProjection()
        }
        return updated
    }

    override suspend fun invalidateFact(factId: String, at: Instant): Boolean {
        val updated = store.invalidateFact(factId, at)
        if (updated) {
            rebuildProjection()
        }
        return updated
    }

    override suspend fun rebuildProjection() {
        val fingerprint = embeddingsFingerprint()
        val activeFacts = store.listFacts(scope = null, statuses = setOf(MemoryFactStatus.ACTIVE))
        val entities = store.listEntitiesByIds(activeFacts.flatMap { listOfNotNull(it.subjectEntityId, it.objectEntityId) }.toSet())
            .associateBy { it.id }
        val episodes = store.listEpisodes(scopes = emptyList(), statuses = setOf("ACTIVE"))
        val docs = buildList {
            activeFacts.forEach { fact ->
                add(
                    MemoryEmbeddingDocRecord(
                        id = "fact:${fact.id}",
                        docType = docTypeFor(fact),
                        sourceRecordType = "fact",
                        sourceRecordId = fact.id!!,
                        scope = fact.scope,
                        text = renderFactDoc(fact, entities),
                        embeddingModelFingerprint = fingerprint,
                    )
                )
            }
            episodes.forEach { episode ->
                add(
                    MemoryEmbeddingDocRecord(
                        id = "episode:${episode.id}",
                        docType = MemoryDocType.EPISODE,
                        sourceRecordType = "episode",
                        sourceRecordId = episode.id!!,
                        scope = episode.scope,
                        text = renderEpisodeDoc(episode),
                        embeddingModelFingerprint = fingerprint,
                    )
                )
            }
        }.distinctBy { it.id }
        store.replaceEmbeddingDocs(docs)
        vectorIndex.rebuild(
            docs = docs.map { doc ->
                MemoryIndexedDoc(
                    id = doc.id!!,
                    sourceRecordId = doc.sourceRecordId,
                    text = doc.text,
                    scopeType = doc.scope.type.name,
                    scopeId = doc.scope.id,
                )
            },
            embeddingsApi = embeddingsApi,
            fingerprint = fingerprint,
        )
    }

    private suspend fun buildEvidenceBundle(input: MemoryWriteInput): Map<String, MemoryEvidenceRecord> {
        val evidenceByRef = linkedMapOf<String, MemoryEvidenceRecord>()
        input.userMessage
            ?.takeIf(String::isNotBlank)
            ?.let { message ->
                val ref = "bundle:user_message:0"
                evidenceByRef[ref] = store.insertEvidence(
                    MemoryEvidenceRecord(
                        scope = input.scope,
                        evidenceType = MemoryEvidenceType.USER_MESSAGE,
                        sourceRef = input.turnRef?.let { "turn:$it:user" } ?: "turn:user",
                        contentExcerpt = message.take(MAX_EXCERPT_LENGTH),
                    )
                )
            }
        input.assistantMessage
            ?.takeIf(String::isNotBlank)
            ?.let { message ->
                val ref = "bundle:assistant_message:0"
                evidenceByRef[ref] = store.insertEvidence(
                    MemoryEvidenceRecord(
                        scope = input.scope,
                        evidenceType = MemoryEvidenceType.ASSISTANT_MESSAGE,
                        sourceRef = input.turnRef?.let { "turn:$it:assistant" } ?: "turn:assistant",
                        contentExcerpt = message.take(MAX_EXCERPT_LENGTH),
                    )
                )
            }
        input.toolOutputs.forEachIndexed { index, output ->
            evidenceByRef["bundle:tool_output:$index"] = store.insertEvidence(
                MemoryEvidenceRecord(
                    scope = input.scope,
                    evidenceType = MemoryEvidenceType.TOOL_OUTPUT,
                    sourceRef = input.turnRef?.let { "turn:$it:tool:$index" } ?: "turn:tool:$index",
                    contentExcerpt = output.take(MAX_EXCERPT_LENGTH),
                )
            )
        }
        input.recentEpisodeSummary
            ?.takeIf(String::isNotBlank)
            ?.let { summary ->
                val ref = "bundle:episode_summary:0"
                evidenceByRef[ref] = store.insertEvidence(
                    MemoryEvidenceRecord(
                        scope = input.scope,
                        evidenceType = MemoryEvidenceType.EPISODE_SUMMARY,
                        sourceRef = input.turnRef?.let { "turn:$it:episode" } ?: "turn:episode",
                        contentExcerpt = summary.take(MAX_EXCERPT_LENGTH),
                    )
                )
            }
        return evidenceByRef
    }

    private suspend fun extractCandidates(
        input: MemoryWriteInput,
        evidenceIndex: Map<String, MemoryEvidenceRecord>,
    ): CandidateExtraction {
        if (evidenceIndex.isEmpty()) {
            return CandidateExtraction(emptyReason = EMPTY_REASON_NO_EVIDENCE)
        }
        val request = LLMRequest.Chat(
            model = chatModelAlias(),
            messages = listOf(
                LLMRequest.Message(
                    role = LLMMessageRole.system,
                    content = memoryCandidateSystemPrompt(allowedScopes = allowedWriteScopes(input.scope)),
                ),
                LLMRequest.Message(
                    role = LLMMessageRole.user,
                    content = memoryCandidateUserPrompt(input = input, evidenceIndex = evidenceIndex),
                ),
            ),
            temperature = 0f,
            maxTokens = 1_400,
        )
        var response: LLMResponse.Chat? = null
        var requestFailureMessage: String? = null
        for (attempt in 1..MEMORY_EXTRACTION_MAX_ATTEMPTS) {
            val result = runCatching { embeddingsApi.message(request) }
            val failed = result.exceptionOrNull()
            if (failed != null) {
                requestFailureMessage = failed.message
                logger.warn("Memory candidate extraction failed: {}", failed.message)
                if (attempt < MEMORY_EXTRACTION_MAX_ATTEMPTS && isTransientMemoryExtractionFailure(message = failed.message)) {
                    logger.info("Retrying memory candidate extraction after transient request failure")
                    continue
                }
                return CandidateExtraction(
                    rejectionReasons = listOf(REJECTION_LLM_PROPOSAL_FAILED),
                    rawOutput = failed.message,
                    rawOutputKind = RAW_OUTPUT_KIND_ERROR,
                    emptyReason = EMPTY_REASON_REQUEST_FAILED,
                )
            }
            val candidateResponse = result.getOrNull() ?: continue
            if (
                candidateResponse is LLMResponse.Chat.Error &&
                attempt < MEMORY_EXTRACTION_MAX_ATTEMPTS &&
                isTransientMemoryExtractionFailure(status = candidateResponse.status, message = candidateResponse.message)
            ) {
                logger.warn("Memory candidate extraction returned transient error {}: {}", candidateResponse.status, candidateResponse.message)
                continue
            }
            response = candidateResponse
            break
        }
        val finalResponse = response ?: return CandidateExtraction(
            rejectionReasons = listOf(REJECTION_LLM_PROPOSAL_FAILED),
            rawOutput = requestFailureMessage,
            rawOutputKind = RAW_OUTPUT_KIND_ERROR,
            emptyReason = EMPTY_REASON_REQUEST_FAILED,
        )

        val functionCall = when (finalResponse) {
            is LLMResponse.Chat.Ok -> finalResponse.choices
                .asReversed()
                .mapNotNull { choice -> choice.message.functionCall }
                .firstOrNull { call -> call.name == MEMORY_CANDIDATE_FUNCTION_NAME }

            else -> null
        }
        if (functionCall != null) {
            return parseCandidatesFromFunctionCall(
                functionCall = functionCall,
                defaultScope = input.scope,
            )
        }

        val content = when (finalResponse) {
            is LLMResponse.Chat.Ok -> finalResponse.choices
                .asReversed()
                .firstOrNull { it.message.content.isNotBlank() }
                ?.message
                ?.content
                ?.trim()
                .orEmpty()

            is LLMResponse.Chat.Error -> {
                logger.warn("Memory candidate extraction returned error {}: {}", finalResponse.status, finalResponse.message)
                return CandidateExtraction(
                    rejectionReasons = listOf(REJECTION_LLM_PROPOSAL_FAILED),
                    rawOutput = finalResponse.message,
                    rawOutputKind = RAW_OUTPUT_KIND_ERROR,
                    emptyReason = EMPTY_REASON_RESPONSE_ERROR,
                )
            }

            else -> ""
        }
        if (content.isBlank()) {
            return CandidateExtraction(emptyReason = EMPTY_REASON_BLANK_CONTENT)
        }
        return parseCandidatesFromContent(
            content = content,
            defaultScope = input.scope,
        )
    }

    private fun isTransientMemoryExtractionFailure(
        status: Int? = null,
        message: String?,
    ): Boolean {
        if (status != null && status < 0) {
            return true
        }
        val normalized = message?.lowercase().orEmpty()
        return normalized.contains("connection error") ||
            normalized.contains("eofexception") ||
            normalized.contains("not enough data available") ||
            normalized.contains("prematurely closed") ||
            normalized.contains("timed out") ||
            normalized.contains("timeout")
    }

    private suspend fun toSnapshots(facts: List<MemoryFactRecord>): List<MemoryFactSnapshot> {
        val entities = store.listEntitiesByIds(facts.flatMap { listOfNotNull(it.subjectEntityId, it.objectEntityId) }.toSet())
            .associateBy { it.id }
        return facts.map { fact ->
            val subject = entities[fact.subjectEntityId]
            val objectEntity = fact.objectEntityId?.let(entities::get)
            MemoryFactSnapshot(
                id = fact.id,
                scope = fact.scope,
                subjectEntityType = subject?.entityType.orEmpty(),
                subjectCanonicalName = subject?.canonicalName.orEmpty(),
                subjectDisplayName = subject?.displayName.orEmpty(),
                subjectNormalizedKey = subject?.normalizedKey.orEmpty(),
                predicate = fact.predicate,
                objectKind = fact.objectKind,
                objectEntityType = objectEntity?.entityType,
                objectEntityCanonicalName = objectEntity?.canonicalName,
                objectEntityDisplayName = objectEntity?.displayName,
                objectEntityNormalizedKey = objectEntity?.normalizedKey,
                objectValueText = fact.objectValueText,
                objectValueJson = fact.objectValueJson,
                slotKey = fact.slotKey,
                confidence = fact.confidence,
                status = fact.status,
                createdAt = fact.createdAt,
            )
        }
    }

    private fun relevantScopes(primaryScope: MemoryScope): List<MemoryScope> =
        buildList {
            add(userScope)
            if (primaryScope != userScope) {
                add(primaryScope)
            }
            workspaceScope?.let(::add)
            projectScope?.let(::add)
        }.distinctBy { "${it.type}:${it.id}" }

    private fun allowedWriteScopes(primaryScope: MemoryScope): List<MemoryScope> =
        relevantScopes(primaryScope)

    private suspend fun collapseFactDocsBySlot(
        docs: List<MemoryEmbeddingDocRecord>,
        scopes: List<MemoryScope>,
    ): List<MemoryEmbeddingDocRecord> {
        if (docs.isEmpty()) return emptyList()
        val activeFactsById = store.listFacts(scope = null, statuses = setOf(MemoryFactStatus.ACTIVE))
            .filter { it.scope in scopes }
            .associateBy { it.id }
        val chosenFactIds = docs
            .mapNotNull { doc ->
                val fact = activeFactsById[doc.sourceRecordId] ?: return@mapNotNull null
                val slotKey = fact.slotKey?.trim()?.lowercase() ?: return@mapNotNull null
                slotKey to fact
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, facts) -> facts.maxBy { it.createdAt }.id }
            .values
            .toSet()
        return docs.filter { doc ->
            val fact = activeFactsById[doc.sourceRecordId]
            val slotKey = fact?.slotKey?.trim()?.lowercase()
            slotKey == null || fact.id in chosenFactIds
        }
    }

    private fun docTypeFor(fact: MemoryFactRecord): MemoryDocType =
        if (
            fact.scope.type == MemoryScopeType.USER ||
            fact.slotKey?.startsWith("user.") == true ||
            fact.predicate.startsWith("prefers_") ||
            fact.predicate == "requires" ||
            fact.predicate == "prohibits"
        ) {
            MemoryDocType.PROFILE
        } else {
            MemoryDocType.FACT
        }

    private fun renderFactDoc(
        fact: MemoryFactRecord,
        entities: Map<String?, ru.souz.agent.memory.MemoryEntityRecord>,
    ): String {
        if (fact.predicate == "requires") {
            return "Important constraint: ${localNormalizeText(fact.objectValueText).orEmpty()}"
        }
        if (fact.predicate == "prefers_language") {
            return "User language: ${fact.objectValueText.orEmpty()}"
        }
        val subject = entities[fact.subjectEntityId]?.displayName ?: fact.subjectEntityId
        val value = fact.objectEntityId?.let { entities[it]?.displayName } ?: fact.objectValueText ?: fact.objectValueJson ?: fact.objectKind.name.lowercase()
        return "${subject.orEmpty()} ${fact.predicate.replace('_', ' ')}: ${value.trim()}"
    }

    private fun renderEpisodeDoc(episode: MemoryEpisodeRecord): String =
        buildString {
            append("Episode: ")
            append(episode.title.trim())
            append(". ")
            append(episode.summary.trim())
            val nextAction = episode.nextAction
            if (!nextAction.isNullOrBlank()) {
                append(" Next: ")
                append(nextAction.trim())
            }
        }

    private fun parseCandidatesFromFunctionCall(
        functionCall: LLMResponse.FunctionCall,
        defaultScope: MemoryScope,
    ): CandidateExtraction =
        runCatching {
            val rawFacts = extractRawFacts(functionCall.arguments)
            val candidates = parseRawFacts(rawFacts, defaultScope)
            if (rawFacts.isNotEmpty() && candidates.isEmpty()) {
                CandidateExtraction(
                    rejectionReasons = listOf(REJECTION_LLM_OUTPUT_INVALID),
                    rawOutput = mapper.writeValueAsString(functionCall.arguments),
                    rawOutputKind = RAW_OUTPUT_KIND_FUNCTION_CALL,
                    emptyReason = EMPTY_REASON_INVALID_FACTS,
                )
            } else {
                CandidateExtraction(
                    candidates = candidates,
                    rawOutput = mapper.writeValueAsString(functionCall.arguments),
                    rawOutputKind = RAW_OUTPUT_KIND_FUNCTION_CALL,
                    emptyReason = rawFacts.takeIf { it.isEmpty() }?.let { EMPTY_REASON_EMPTY_FACTS },
                )
            }
        }.getOrElse { error ->
            logger.warn("Memory candidate extraction returned invalid tool arguments: {}", error.message)
            CandidateExtraction(
                rejectionReasons = listOf(REJECTION_LLM_OUTPUT_INVALID),
                rawOutput = runCatching { mapper.writeValueAsString(functionCall.arguments) }.getOrNull(),
                rawOutputKind = RAW_OUTPUT_KIND_FUNCTION_CALL,
                emptyReason = EMPTY_REASON_INVALID_FUNCTION_CALL,
            )
        }

    private fun parseCandidatesFromContent(
        content: String,
        defaultScope: MemoryScope,
    ): CandidateExtraction {
        val payload = decodeContentPayload(content)
            ?: return CandidateExtraction(
                rawOutput = content,
                rawOutputKind = RAW_OUTPUT_KIND_CONTENT,
                emptyReason = classifyEmptyContent(content),
            )
        val rawFacts = extractRawFacts(payload)
        val candidates = parseRawFacts(rawFacts, defaultScope)
        return if (rawFacts.isNotEmpty() && candidates.isEmpty()) {
            CandidateExtraction(
                rejectionReasons = listOf(REJECTION_LLM_OUTPUT_INVALID),
                rawOutput = content,
                rawOutputKind = RAW_OUTPUT_KIND_CONTENT,
                emptyReason = EMPTY_REASON_INVALID_FACTS,
            )
        } else {
            CandidateExtraction(
                candidates = candidates,
                rawOutput = content,
                rawOutputKind = RAW_OUTPUT_KIND_CONTENT,
                emptyReason = rawFacts.takeIf { it.isEmpty() }?.let { EMPTY_REASON_EMPTY_FACTS },
            )
        }
    }

    private fun extractRawFacts(raw: Any?): List<Any?> =
        when (raw) {
            is List<*> -> raw
            is Map<*, *> -> when {
                raw["facts"] is List<*> -> raw["facts"] as List<*>
                raw["candidates"] is List<*> -> raw["candidates"] as List<*>
                else -> listOf(raw)
            }
            null -> emptyList()
            else -> emptyList()
        }

    private fun parseRawFacts(
        rawCandidates: List<Any?>,
        defaultScope: MemoryScope,
    ): List<MemoryCandidate> =
        rawCandidates.mapNotNull { rawCandidate ->
            runCatching {
                val payload = mapper.convertValue(rawCandidate, LinkedHashMap::class.java) as Map<String, Any?>
                mapper.convertValue(
                    normalizeCandidatePayload(payload, defaultScope),
                    MemoryCandidate::class.java,
                )
            }.onFailure { error ->
                logger.warn("Skipping invalid memory candidate payload: {}", error.message)
            }.getOrNull()
        }

    private fun decodeContentPayload(content: String): Any? {
        val normalized = content.trim()
        if (normalized.isBlank() || normalized.equals("null", ignoreCase = true)) {
            return null
        }
        candidateJsonTexts(normalized).forEach { candidate ->
            runCatching {
                mapper.readValue(candidate, Any::class.java)
            }.onSuccess { payload ->
                return payload
            }
        }
        logger.info("Memory candidate extraction ignored non-JSON content")
        return null
    }

    private fun classifyEmptyContent(content: String): String =
        when {
            content.isBlank() -> EMPTY_REASON_BLANK_CONTENT
            content.trim().equals("null", ignoreCase = true) -> EMPTY_REASON_NULL_CONTENT
            else -> EMPTY_REASON_NON_JSON_CONTENT
        }

    private fun candidateJsonTexts(content: String): List<String> =
        buildList {
            add(content)
            stripMarkdownCodeFence(content)?.let(::add)
            extractJsonSnippet(content)?.let(::add)
            stripMarkdownCodeFence(extractJsonSnippet(content).orEmpty())?.let(::add)
        }.map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()

    private fun stripMarkdownCodeFence(content: String): String? {
        val trimmed = content.trim()
        if (!trimmed.startsWith("```") || !trimmed.endsWith("```")) {
            return null
        }
        val lines = trimmed.lines()
        if (lines.size < 2) {
            return null
        }
        return lines.drop(1).dropLast(1).joinToString("\n").trim()
    }

    private fun extractJsonSnippet(content: String): String? {
        val objectStart = content.indexOf('{').takeIf { it >= 0 }
        val arrayStart = content.indexOf('[').takeIf { it >= 0 }
        val start = listOfNotNull(objectStart, arrayStart).minOrNull() ?: return null
        val objectEnd = content.lastIndexOf('}').takeIf { it >= start } ?: -1
        val arrayEnd = content.lastIndexOf(']').takeIf { it >= start } ?: -1
        val end = maxOf(objectEnd, arrayEnd)
        if (end < start) {
            return null
        }
        return content.substring(start, end + 1).trim()
    }

    private fun normalizeCandidatePayload(
        payload: Map<String, Any?>,
        defaultScope: MemoryScope,
    ): Map<String, Any?> {
        val normalized = LinkedHashMap(payload)
        val subjectName = payload["subject"]?.toString()?.trim().takeUnless { it.isNullOrBlank() } ?: DEFAULT_SUBJECT_NAME
        val subjectType = normalizeEntityType(payload["subjectType"]) ?: DEFAULT_SUBJECT_TYPE
        val effectiveSubjectType = if (subjectName == DEFAULT_SUBJECT_NAME) DEFAULT_SUBJECT_TYPE else subjectType
        normalized["subjectEntityType"] = payload["subjectEntityType"] ?: effectiveSubjectType
        normalized["subjectCanonicalName"] = payload["subjectCanonicalName"] ?: subjectName
        normalized["subjectDisplayName"] = payload["subjectDisplayName"] ?: subjectName
        normalized["subjectNormalizedKey"] = payload["subjectNormalizedKey"] ?: normalizeEntityKey(
            entityType = effectiveSubjectType,
            canonicalName = subjectName,
        )
        normalized["scope"] = normalizeScopePayload(payload["scope"], defaultScope)
        normalized["evidenceRefs"] = normalizeEvidenceRefsPayload(payload["evidenceRefs"])
        normalized["confidence"] = payload["confidence"]?.let(::normalizeConfidencePayload) ?: DEFAULT_CONFIDENCE
        normalized["reasonToStore"] = payload["reasonToStore"]?.toString()?.trim().takeUnless { it.isNullOrBlank() }
            ?: DEFAULT_REASON_TO_STORE
        normalized["objectKind"] = normalizeObjectKind(payload)
        normalizeObjectPayload(payload, normalized)
        normalized["suggestedStatus"] = normalizeEnumName(
            payload["suggestedStatus"] ?: MemoryFactStatus.ACTIVE.name,
            MemoryFactStatus.entries.map { it.name },
        ) ?: MemoryFactStatus.ACTIVE.name
        normalized["conflictPolicy"] = normalizeEnumName(
            payload["conflictPolicy"]
                ?: if (!payload["slotKey"]?.toString().isNullOrBlank()) MemoryConflictPolicy.SINGLE_ACTIVE_PER_SLOT.name else MemoryConflictPolicy.ALLOW_MULTIPLE_ACTIVE.name,
            MemoryConflictPolicy.entries.map { it.name },
        ) ?: MemoryConflictPolicy.ALLOW_MULTIPLE_ACTIVE.name
        return normalized
    }

    private fun normalizeObjectKind(payload: Map<String, Any?>): String =
        normalizeEnumName(
            payload["objectKind"]
                ?: payload["kind"]
                ?: when (payload["value"]) {
                    is Boolean -> MemoryObjectKind.BOOLEAN.name
                    is Number -> MemoryObjectKind.NUMBER.name
                    is Map<*, *>, is List<*> -> MemoryObjectKind.JSON.name
                    else -> MemoryObjectKind.TEXT.name
                },
            MemoryObjectKind.entries.map { it.name },
        ) ?: MemoryObjectKind.TEXT.name

    private fun normalizeObjectPayload(
        payload: Map<String, Any?>,
        normalized: LinkedHashMap<String, Any?>,
    ) {
        if (payload["objectValueText"] != null || payload["objectValueJson"] != null || payload["objectEntityCanonicalName"] != null) {
            payload["objectValueText"]?.let { normalized["objectValueText"] = it.toString() }
            payload["objectValueJson"]?.let { value ->
                normalized["objectValueJson"] = if (value is String) value else mapper.writeValueAsString(value)
            }
            payload["objectEntityType"]?.let { normalized["objectEntityType"] = normalizeEntityType(it) ?: it.toString() }
            payload["objectEntityCanonicalName"]?.let { normalized["objectEntityCanonicalName"] = it.toString() }
            payload["objectEntityDisplayName"]?.let { normalized["objectEntityDisplayName"] = it.toString() }
            payload["objectEntityNormalizedKey"]?.let { normalized["objectEntityNormalizedKey"] = it.toString() }
            return
        }
        when (normalized["objectKind"]) {
            MemoryObjectKind.ENTITY.name -> {
                val valueText = payload["value"]?.toString()?.trim().takeUnless { it.isNullOrBlank() } ?: return
                val objectType = normalizeEntityType(payload["objectType"]) ?: "ENTITY"
                normalized["objectEntityType"] = objectType
                normalized["objectEntityCanonicalName"] = valueText
                normalized["objectEntityDisplayName"] = valueText
                normalized["objectEntityNormalizedKey"] = normalizeEntityKey(
                    entityType = objectType,
                    canonicalName = valueText,
                )
            }

            MemoryObjectKind.JSON.name -> {
                payload["value"]?.let { value ->
                    normalized["objectValueJson"] = if (value is String) value else mapper.writeValueAsString(value)
                }
            }

            else -> {
                payload["value"]?.let { normalized["objectValueText"] = it.toString() }
            }
        }
    }

    private fun normalizeScopePayload(
        rawScope: Any?,
        defaultScope: MemoryScope,
    ): Map<String, String> {
        val normalizedDefault = mapOf(
            "type" to defaultScope.type.name,
            "id" to defaultScope.id,
        )
        val scopeMap = rawScope as? Map<*, *>
        if (scopeMap != null) {
            val type = scopeMap["type"]?.toString()
            val id = scopeMap["id"]?.toString()
            val normalizedType = normalizeScopeType(type)
            if (normalizedType != null && !id.isNullOrBlank()) {
                return mapOf(
                    "type" to normalizedType.name,
                    "id" to id.trim(),
                )
            }
            return normalizedDefault
        }
        val scopeText = rawScope?.toString()?.trim().orEmpty()
        if (scopeText.isBlank()) {
            return normalizedDefault
        }
        val parts = scopeText.split(":", limit = 2)
        val type = normalizeScopeType(parts.firstOrNull())
        val id = parts.getOrNull(1)?.trim()
        return if (type != null && !id.isNullOrBlank()) {
            mapOf(
                "type" to type.name,
                "id" to id,
            )
        } else {
            normalizedDefault
        }
    }

    private fun normalizeScopeType(rawType: String?): MemoryScopeType? {
        val normalized = rawType
            ?.trim()
            ?.replace('-', '_')
            ?.replace(' ', '_')
            ?.uppercase()
            ?: return null
        return MemoryScopeType.entries.firstOrNull { it.name == normalized }
    }

    private fun normalizeEntityType(rawType: Any?): String? =
        rawType?.toString()
            ?.trim()
            ?.replace('-', '_')
            ?.replace(' ', '_')
            ?.uppercase()
            ?.takeIf(String::isNotEmpty)

    private fun normalizeEntityKey(
        entityType: String,
        canonicalName: String,
    ): String {
        val normalizedName = canonicalName.trim()
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "item" }
        return "${entityType.lowercase()}/$normalizedName"
    }

    private fun normalizeEvidenceRefsPayload(rawEvidenceRefs: Any?): List<String> =
        when (rawEvidenceRefs) {
            is List<*> -> rawEvidenceRefs.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            is String -> listOfNotNull(rawEvidenceRefs.trim().takeIf(String::isNotEmpty))
            null -> emptyList()
            else -> listOfNotNull(rawEvidenceRefs.toString().trim().takeIf(String::isNotEmpty))
        }

    private fun normalizeConfidencePayload(rawConfidence: Any): Double? =
        when (rawConfidence) {
            is Number -> rawConfidence.toDouble()
            is String -> rawConfidence.trim().toDoubleOrNull()
            else -> null
        }

    private fun normalizeEnumName(
        rawValue: Any,
        allowedNames: List<String>,
    ): String? {
        val normalized = rawValue.toString()
            .trim()
            .replace('-', '_')
            .replace(' ', '_')
            .uppercase()
            .takeIf(String::isNotEmpty)
            ?: return null
        return allowedNames.firstOrNull { it.equals(normalized, ignoreCase = true) }
    }

    private fun memoryCandidateSystemPrompt(allowedScopes: List<MemoryScope>): String =
        buildString {
            appendLine("You extract future-useful long-term memory facts from evidence.")
            appendLine("Store stable user preferences, long-lived goals, durable plans, recurring instructions, and persistent interests when explicitly supported by evidence.")
            appendLine("Decide only what should be remembered for future turns. Do not summarize the whole conversation.")
            appendLine("Return JSON only with the shape: {\"facts\":[...]}.")
            appendLine("If there are no durable facts, return {\"facts\":[]}.")
            appendLine("Never return null, an empty string, markdown fences, or explanatory prose.")
            appendLine("The top-level JSON value must always be an object with a facts array.")
            appendLine("Each fact should be simple.")
            appendLine("Required fields: predicate, value, evidenceRefs.")
            appendLine("Optional fields: subject, scope, slotKey, confidence, reasonToStore, conflictPolicy.")
            appendLine("Do not add markdown fences or explanatory text around the JSON.")
            appendLine("If subject is omitted, current_user will be used.")
            appendLine("Scope may be either a TYPE:id string or an object with type and id. If omitted, current scope will be used.")
            appendLine("evidenceRefs may be a single ref string or an array of refs.")
            appendLine("Use suggestedStatus=ACTIVE only.")
            appendLine("Set conflictPolicy=SINGLE_ACTIVE_PER_SLOT and provide slotKey when a new fact should replace the previous current fact for that logical slot.")
            appendLine("Do not propose greetings, thanks, small talk, or one-off chatter.")
            appendLine("Do not store city, timezone, or other runtime metadata unless the user explicitly states a durable preference or override.")
            appendLine("Do not invent evidence refs. Only use refs from the evidence bundle.")
            appendLine("Prefer zero candidates over weak or speculative memory.")
            appendLine("Predicate names are open-ended. The examples below are illustrative, not an allowlist.")
            appendLine("""Example positive: if the evidence says "My main goal is to work at Anthropic.", return {"facts":[{"predicate":"career_goal","value":"work at Anthropic","evidenceRefs":["bundle:user_message:0"],"slotKey":"user.goals.primary_career","confidence":0.95,"reasonToStore":"Explicit long-lived career goal","conflictPolicy":"SINGLE_ACTIVE_PER_SLOT"}]}""")
            appendLine("""Example positive: if the evidence says "If I do not explicitly ask to save to notes, do not do it.", return {"facts":[{"predicate":"notes_save_policy","value":"only save to notes on explicit request","evidenceRefs":["bundle:user_message:0"],"slotKey":"user.preferences.notes_save_policy","confidence":0.95,"reasonToStore":"Explicit persistent workflow preference","conflictPolicy":"SINGLE_ACTIVE_PER_SLOT"}]}""")
            appendLine("""Example negative: if the evidence says "Thanks, that was helpful.", return {"facts":[]}""")
            appendLine("Allowed scopes:")
            allowedScopes.forEach { scope -> appendLine("- ${scope.type.name}:${scope.id}") }
        }

    private fun memoryCandidateUserPrompt(
        input: MemoryWriteInput,
        evidenceIndex: Map<String, MemoryEvidenceRecord>,
    ): String =
        buildString {
            appendLine("Trigger: ${input.triggerType.name}")
            appendLine("Current scope: ${input.scope.type.name}:${input.scope.id}")
            appendLine("When a durable fact is stated directly by the user in this turn, prefer citing bundle:user_message:0.")
            appendLine("Evidence bundle:")
            evidenceIndex.forEach { (ref, evidence) ->
                appendLine("- ref=$ref type=${evidence.evidenceType.name} source=${evidence.sourceRef}")
                evidence.contentExcerpt?.takeIf(String::isNotBlank)?.let { excerpt ->
                    appendLine("  excerpt=${excerpt.take(MAX_EXCERPT_LENGTH)}")
                }
            }
            if (input.recentFacts.isNotEmpty()) {
                appendLine("Recent facts are context for deduping and superseding. Do not copy them unless the current turn provides new supporting evidence.")
                appendLine("Recent facts:")
                input.recentFacts.forEach { fact ->
                    appendLine("- ${fact.scope.type.name}:${fact.scope.id} ${fact.predicate} slot=${fact.slotKey ?: "-"} status=${fact.status.name} value=${fact.objectValueText ?: fact.objectValueJson ?: fact.objectEntityId ?: fact.objectKind.name}")
                }
            }
            input.recentEpisodeSummary?.takeIf(String::isNotBlank)?.let { summary ->
                appendLine("Recent episode summary:")
                appendLine(summary.take(MAX_EXCERPT_LENGTH))
            }
        }

    private companion object {
        private const val MAX_EXCERPT_LENGTH = 400
        private const val MEMORY_CANDIDATE_FUNCTION_NAME = "propose_memory_candidates"
        private const val MEMORY_EXTRACTION_MAX_ATTEMPTS = 2
        private const val REJECTION_LLM_OUTPUT_INVALID = "LLM_OUTPUT_INVALID"
        private const val REJECTION_LLM_PROPOSAL_FAILED = "LLM_PROPOSAL_FAILED"
        private const val RAW_OUTPUT_KIND_CONTENT = "CONTENT"
        private const val RAW_OUTPUT_KIND_FUNCTION_CALL = "FUNCTION_CALL"
        private const val RAW_OUTPUT_KIND_ERROR = "ERROR"
        private const val EMPTY_REASON_NO_EVIDENCE = "NO_EVIDENCE"
        private const val EMPTY_REASON_REQUEST_FAILED = "REQUEST_FAILED"
        private const val EMPTY_REASON_RESPONSE_ERROR = "RESPONSE_ERROR"
        private const val EMPTY_REASON_BLANK_CONTENT = "BLANK_CONTENT"
        private const val EMPTY_REASON_NULL_CONTENT = "NULL_CONTENT"
        private const val EMPTY_REASON_NON_JSON_CONTENT = "NON_JSON_CONTENT"
        private const val EMPTY_REASON_EMPTY_FACTS = "EMPTY_FACTS"
        private const val EMPTY_REASON_INVALID_FACTS = "INVALID_FACTS"
        private const val EMPTY_REASON_INVALID_FUNCTION_CALL = "INVALID_FUNCTION_CALL"
        private const val DEFAULT_SUBJECT_NAME = "current_user"
        private const val DEFAULT_SUBJECT_TYPE = "USER"
        private const val DEFAULT_REASON_TO_STORE = "Potentially useful future fact from the current turn."
        private const val DEFAULT_CONFIDENCE = 0.9
    }
}

private data class LoggedCandidate(
    val candidate: MemoryCandidate,
    val accepted: Boolean,
    val rejectionReason: String? = null,
)

private data class CandidateExtraction(
    val candidates: List<MemoryCandidate> = emptyList(),
    val rejectionReasons: List<String> = emptyList(),
    val rawOutput: String? = null,
    val rawOutputKind: String? = null,
    val emptyReason: String? = null,
)

private data class StoredExtractionAuditEnvelope(
    val audits: List<LoggedCandidate> = emptyList(),
    val rawOutput: String? = null,
    val rawOutputKind: String? = null,
    val emptyReason: String? = null,
)

private fun localEstimateTokenCount(text: String): Int =
    kotlin.math.ceil(text.length / 4.0).toInt()

private fun localNormalizeText(value: String?): String? =
    value
        ?.trim()
        ?.replace(Regex("\\s+"), " ")
        ?.takeIf(String::isNotEmpty)
