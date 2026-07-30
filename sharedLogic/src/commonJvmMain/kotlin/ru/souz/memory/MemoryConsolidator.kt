package ru.souz.memory

import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.restJsonMapper

data class MemoryConsolidationInput(
    val ownerId: MemoryOwnerId,
    val scope: MemoryScope,
    val facts: List<MemoryFactDetails>,
    val modelAlias: String? = null,
)

data class MemoryConsolidationCandidate(
    val kind: MemoryFactKind,
    val title: String,
    val body: String,
    val canonicalKey: String?,
    val confidence: Float,
    val importance: Float = confidence.coerceIn(0f, 1f),
    val sourceFactIds: List<String> = emptyList(),
    val evidenceSourceEventIds: List<String> = emptyList(),
)

class MemoryConsolidationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

data class MemoryConsolidationEvaluation(
    val accepted: Boolean,
    val reason: String,
)

interface MemoryConsolidator {
    suspend fun consolidate(input: MemoryConsolidationInput): List<MemoryConsolidationCandidate>
}

interface MemoryConsolidationQualityGate {
    fun evaluate(
        input: MemoryConsolidationInput,
        candidates: List<MemoryConsolidationCandidate>,
    ): MemoryConsolidationEvaluation
}

object NoopMemoryConsolidator : MemoryConsolidator {
    override suspend fun consolidate(input: MemoryConsolidationInput): List<MemoryConsolidationCandidate> =
        emptyList()
}

object DefaultMemoryConsolidationQualityGate : MemoryConsolidationQualityGate {
    override fun evaluate(
        input: MemoryConsolidationInput,
        candidates: List<MemoryConsolidationCandidate>,
    ): MemoryConsolidationEvaluation {
        if (input.facts.size < 2) return reject("insufficient_facts")
        if (candidates.isEmpty()) return reject("no_candidates")
        if (candidates.any { it.title.isBlank() || it.body.isBlank() }) return reject("blank_candidate")

        val factsById = input.facts.associateBy { it.fact.id }
        if (candidates.any { it.sourceFactIds.size < 2 }) return reject("insufficient_source_facts")
        val sourceFactIds = candidates.flatMap { it.sourceFactIds }
        if (sourceFactIds.any { it !in factsById }) return reject("unknown_source_fact")
        if (sourceFactIds.size != sourceFactIds.distinct().size) return reject("overlapping_source_facts")
        if (candidates.any { it.evidenceSourceEventIds.isEmpty() }) return reject("missing_evidence")
        if (candidates.any { it.confidence < MIN_CONFIDENCE }) return reject("low_confidence")

        val knownEvidenceIds = input.facts
            .flatMap { details ->
                details.evidence.mapNotNull { detail ->
                    detail.sourceEvent.id.takeIf { !detail.evidence.evidenceText.isNullOrBlank() }
                }
            }
            .toSet()
        val unknownEvidence = candidates
            .flatMap { it.evidenceSourceEventIds }
            .any { it !in knownEvidenceIds }
        if (unknownEvidence) return reject("unknown_evidence")
        val incorrectlyLinkedEvidence = candidates.any { candidate ->
            val sourceEvidenceIds = candidate.sourceFactIds
                .mapNotNull(factsById::get)
                .flatMap { details -> details.evidence.map { it.sourceEvent.id } }
                .toSet()
            candidate.evidenceSourceEventIds.any { it !in sourceEvidenceIds }
        }
        if (incorrectlyLinkedEvidence) return reject("evidence_not_linked_to_source_fact")

        val sourceChars = sourceFactIds.sumOf { factId ->
            factsById.getValue(factId).fact.let { it.title.length + it.body.length }
        }.coerceAtLeast(1)
        val candidateChars = candidates.sumOf { it.title.length + it.body.length }
        if (candidateChars >= sourceChars) return reject("not_text_compact")
        return MemoryConsolidationEvaluation(
            accepted = true,
            reason = "accepted",
        )
    }

    private fun reject(reason: String): MemoryConsolidationEvaluation =
        MemoryConsolidationEvaluation(
            accepted = false,
            reason = reason,
        )

    private const val MIN_CONFIDENCE = 0.65f
}

class LlmMemoryConsolidator(
    private val api: LLMChatAPI,
    private val settingsProvider: SettingsProvider,
    private val defaultModelAlias: () -> String = { settingsProvider.gigaModel.alias },
) : MemoryConsolidator {
    private val logger = LoggerFactory.getLogger(LlmMemoryConsolidator::class.java)

    override suspend fun consolidate(input: MemoryConsolidationInput): List<MemoryConsolidationCandidate> {
        if (input.facts.size < 2) return emptyList()
        val first = request(input, INITIAL_MAX_TOKENS)
        return when (first) {
            is LLMResponse.Chat.Error -> throw providerFailure(first)
            is LLMResponse.Chat.Ok -> {
                try {
                    parseResponse(first)
                } catch (_: MemoryConsolidationException) {
                    when (val retry = request(input, RETRY_MAX_TOKENS)) {
                        is LLMResponse.Chat.Error -> throw providerFailure(retry)
                        is LLMResponse.Chat.Ok -> parseResponse(retry)
                    }
                }
            }
        }
    }

    private suspend fun request(input: MemoryConsolidationInput, maxTokens: Int): LLMResponse.Chat =
        api.message(
            LLMRequest.Chat(
                model = input.modelAlias ?: defaultModelAlias(),
                messages = listOf(
                    LLMRequest.Message(
                        role = LLMMessageRole.system,
                        content = CONSOLIDATOR_SYSTEM_PROMPT,
                    ),
                    LLMRequest.Message(
                        role = LLMMessageRole.user,
                        content = buildUserPrompt(input),
                    ),
                ),
                temperature = 0f,
                maxTokens = maxTokens,
                localOutputFormat = LLMRequest.LocalOutputFormat.RAW,
            )
        )

    private fun parseResponse(response: LLMResponse.Chat.Ok): List<MemoryConsolidationCandidate> {
        val choice = response.choices.firstOrNull()
            ?: throw MemoryConsolidationException("Memory consolidator returned no choices")
        if (choice.finishReason == LLMResponse.FinishReason.length) {
            throw MemoryConsolidationException("Memory consolidator output was truncated")
        }
        return parseCandidates(choice.message.content)
    }

    private fun providerFailure(error: LLMResponse.Chat.Error) = MemoryConsolidationException(
        "Memory consolidator failed: ${error.status} ${error.message}"
    )

    private fun parseCandidates(raw: String): List<MemoryConsolidationCandidate> {
        val json = raw.trim().extractJsonArray()
        if (json.isEmpty()) throw MemoryConsolidationException("Memory consolidator returned malformed JSON")
        return try {
            restJsonMapper.readValue<List<ConsolidatorCandidate>>(json)
                .mapNotNull { candidate ->
                    val title = MemorySanitizer.redact(candidate.title.trim()).takeIf(String::isNotBlank)
                    val body = MemorySanitizer.redact(candidate.body.trim()).takeIf(String::isNotBlank)
                    if (title == null || body == null) {
                        null
                    } else {
                        MemoryConsolidationCandidate(
                            kind = candidate.kind,
                            title = title,
                            body = body,
                            canonicalKey = normalizeCanonicalKey(candidate.canonicalKey),
                            confidence = candidate.confidence.coerceIn(0f, 1f),
                            importance = (candidate.importance ?: candidate.confidence).coerceIn(0f, 1f),
                            sourceFactIds = candidate.sourceFactIds.distinct(),
                            evidenceSourceEventIds = candidate.evidenceSourceEventIds.distinct(),
                        )
                    }
                }
        } catch (error: Exception) {
            logger.warn("Failed to parse memory consolidator output: {}", error.message)
            throw MemoryConsolidationException("Memory consolidator returned malformed JSON", error)
        }
    }

    private fun buildUserPrompt(input: MemoryConsolidationInput): String = buildString {
        appendLine("Scope: ${input.scope.type}")
        appendLine()
        appendLine("Current memory region:")
        input.facts.forEachIndexed { index, details ->
            val fact = details.fact
            appendLine("${index + 1}. factId=${fact.id}")
            appendLine("kind=${fact.kind}")
            appendLine("title=${MemorySanitizer.redact(fact.title)}")
            appendLine("body=${MemorySanitizer.redact(fact.body)}")
            appendLine("canonicalKey=${fact.canonicalKey.orEmpty()}")
            val evidenceIds = details.evidence.joinToString(", ") { it.sourceEvent.id }
            appendLine("evidenceSourceEventIds=$evidenceIds")
            appendLine()
        }
        appendLine("Evidence snippets:")
        input.facts
            .flatMap { it.evidence }
            .distinctBy { it.sourceEvent.id to it.evidence.evidenceText }
            .mapNotNull { detail ->
                detail.evidence.evidenceText
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?.let { evidenceText -> detail to evidenceText }
            }
            .forEach { (detail, evidenceText) ->
                appendLine("[${detail.sourceEvent.id}] ${MemorySanitizer.redact(evidenceText).take(MAX_EVIDENCE_CHARS)}")
            }
    }

    private fun String.extractJsonArray(): String {
        val trimmed = trim()
        if (trimmed.startsWith('[') && trimmed.endsWith(']')) return trimmed
        val start = trimmed.indexOf('[')
        val end = trimmed.lastIndexOf(']')
        if (start >= 0 && end > start) return trimmed.substring(start, end + 1)
        return ""
    }

    private data class ConsolidatorCandidate(
        val kind: MemoryFactKind = MemoryFactKind.SEMANTIC,
        val title: String = "",
        val body: String = "",
        val canonicalKey: String? = null,
        val confidence: Float = 0f,
        val importance: Float? = null,
        val sourceFactIds: List<String> = emptyList(),
        val evidenceSourceEventIds: List<String> = emptyList(),
    )

    private companion object {
        private const val INITIAL_MAX_TOKENS = 1_200
        private const val RETRY_MAX_TOKENS = 2_400
        private const val MAX_EVIDENCE_CHARS = 1_200
        private const val CONSOLIDATOR_SYSTEM_PROMPT = """
You are the offline memory consolidator for a desktop AI agent.

Find duplicate or overlapping facts in the provided memory region and return only their compact replacements.
Use only the listed evidence. Do not invent user preferences, project facts, dates, IDs, or decisions.
Prefer one compact fact when several facts describe the same durable rule, decision, preference, or project constraint.
Omitted facts remain active and unchanged. Return [] when there are no duplicates or evidence is insufficient.

Return JSON array only.

Each item:
{
  "kind": "PREFERENCE|PROCEDURE|PROJECT_RULE|PROJECT_DECISION|SEMANTIC|EPISODE_NOTE",
  "title": "...",
  "body": "...",
  "canonicalKey": "controlled.semantic.key.or_null",
  "confidence": 0.0,
  "importance": 0.0,
  "sourceFactIds": ["fact-id"],
  "evidenceSourceEventIds": ["source-event-id"]
}

Every sourceFactIds value must be copied from the prompt and each replacement must cite at least two source facts.
Every evidenceSourceEventIds value must be copied from the prompt.
"""
    }
}
