package ru.souz.memory

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Path
import java.time.Instant
import java.util.LinkedHashMap
import org.slf4j.LoggerFactory
import ru.souz.agent.memory.DefaultMemoryCandidateValidator
import ru.souz.agent.memory.MemoryCandidate
import ru.souz.agent.memory.MemoryCandidateValidationInput
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

class MemoryRuntimeServices(
    private val store: MemoryCanonicalStore,
    private val embeddingsApi: LLMChatAPI,
    private val embeddingsFingerprint: () -> String,
    private val userScope: MemoryScope,
    vectorIndexDir: Path,
    private val workspaceScope: MemoryScope? = null,
    private val projectScope: MemoryScope? = null,
) : MemoryRuntimeServicesContract {
    private val logger = LoggerFactory.getLogger(MemoryRuntimeServices::class.java)
    private val validator = DefaultMemoryCandidateValidator()
    private val mapper = jacksonObjectMapper().findAndRegisterModules()
    private val vectorIndex = MemoryVectorIndex(vectorIndexDir)

    override suspend fun write(input: MemoryWriteInput): MemoryWriteResult {
        val evidenceIndex = buildEvidenceBundle(input)
        val activeFacts = store.listFacts(scope = null, statuses = setOf(MemoryFactStatus.ACTIVE))
        val snapshots = toSnapshots(activeFacts)
        val candidates = extractCandidates(input)
        val acceptedFacts = mutableListOf<MemoryFactRecord>()
        val rejectionReasons = mutableListOf<String>()

        candidates.forEach { candidate ->
            val validation = validator.validate(
                MemoryCandidateValidationInput(
                    candidate = candidate,
                    evidenceIndex = evidenceIndex,
                    activeFacts = snapshots.filter { it.scope == candidate.scope },
                    acceptedCountThisTurn = acceptedFacts.size,
                )
            )
            if (!validation.accepted) {
                rejectionReasons += validation.rejectionReason?.name.orEmpty()
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
        }

        val attemptId = store.logWriteAttempt(
            scope = input.scope,
            turnRef = input.turnRef,
            triggerType = input.triggerType,
            inputExcerpt = input.userMessage?.take(MAX_EXCERPT_LENGTH) ?: input.assistantMessage?.take(MAX_EXCERPT_LENGTH),
            candidatesJson = mapper.writeValueAsString(candidates),
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

            val packets = selected.values
                .take(request.maxItems.coerceAtLeast(0))
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
        return evidenceByRef
    }

    private fun extractCandidates(input: MemoryWriteInput): List<MemoryCandidate> {
        val userText = input.userMessage?.trim().orEmpty()
        if (userText.isBlank()) return emptyList()
        return buildList {
            extractTestsFirstConstraint(userText)?.let { constraint ->
                add(
                    MemoryCandidate(
                        subjectEntityType = "USER",
                        subjectCanonicalName = "current_user",
                        subjectDisplayName = "current_user",
                        subjectNormalizedKey = "user/current_user",
                        predicate = "requires",
                        objectKind = MemoryObjectKind.TEXT,
                        objectValueText = constraint,
                        scope = userScope,
                        slotKey = "user.constraints.tests_first",
                        confidence = 0.96,
                        reasonToStore = "Stable explicit user constraint",
                        evidenceRefs = listOf("bundle:user_message:0"),
                    )
                )
            }
            extractLanguagePreference(userText)?.let { language ->
                add(
                    MemoryCandidate(
                        subjectEntityType = "USER",
                        subjectCanonicalName = "current_user",
                        subjectDisplayName = "current_user",
                        subjectNormalizedKey = "user/current_user",
                        predicate = "prefers_language",
                        objectKind = MemoryObjectKind.TEXT,
                        objectValueText = language,
                        scope = userScope,
                        slotKey = "user.profile.language",
                        confidence = 0.96,
                        reasonToStore = "Stable explicit user language preference",
                        evidenceRefs = listOf("bundle:user_message:0"),
                    )
                )
            }
        }
    }

    private fun extractTestsFirstConstraint(text: String): String? {
        val normalized = text.lowercase()
        if (!normalized.contains("test")) return null
        val looksExplicit =
            normalized.contains("before implement") ||
                normalized.contains("before implementation") ||
                normalized.contains("first") ||
                normalized.contains("сначала")
        if (!looksExplicit) return null
        return if (normalized.contains("unit")) {
            "write unit tests first"
        } else {
            "write tests first"
        }
    }

    private fun extractLanguagePreference(text: String): String? {
        val normalized = text.lowercase()
        return when {
            normalized.contains("по-русски") ||
                normalized.contains("на русском") ||
                normalized.contains("write in russian") -> "ru"

            normalized.contains("in english") ||
                normalized.contains("на английском") -> "en"

            else -> null
        }
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

    private companion object {
        private const val MAX_EXCERPT_LENGTH = 400
    }
}

private fun localEstimateTokenCount(text: String): Int =
    kotlin.math.ceil(text.length / 4.0).toInt()

private fun localNormalizeText(value: String?): String? =
    value
        ?.trim()
        ?.replace(Regex("\\s+"), " ")
        ?.takeIf(String::isNotEmpty)
