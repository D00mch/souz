package ru.souz.memory

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

class MemoryCaptureService(
    private val memoryService: MemoryService,
    private val writer: MemoryWriter,
) {
    private val captureMutex = Mutex()

    @Suppress("DEPRECATION")
    suspend fun captureAfterTurn(input: MemoryCaptureInput): List<MemoryFact> = captureMutex.withLock {
        val intent = parseExplicitMemoryIntent(input.userMessage)
        if (intent == ExplicitMemoryIntent.DO_NOT_CAPTURE_THIS_TURN) return@withLock emptyList()
        if (intent == ExplicitMemoryIntent.FORGET_EXISTING || intent == ExplicitMemoryIntent.DELETE_EXISTING) {
            memoryService.forgetFromText(
                context = input.context,
                text = input.userMessage,
                hardDelete = intent == ExplicitMemoryIntent.DELETE_EXISTING,
            )
            return@withLock emptyList()
        }

        val isExplicitPositive = intent == ExplicitMemoryIntent.REMEMBER_SIGNAL
        val candidates = extractCandidates(input, isExplicitPositive)
        val allowedScopes = (input.scopes + input.context.allowedRetrievalScopes())
            .map { it.normalized() }
            .toSet()
        val processedCandidates = candidates
            .filter { candidate -> isValidCandidate(candidate, isExplicitPositive) }
            .mapNotNull { candidate ->
                val legacyScope = candidate.scope?.normalized()?.takeIf { it in allowedScopes }
                val targetScope = legacyScope
                    ?: input.context.resolveRequestedScope(candidate.requestedScope, candidate.kind)?.normalized()
                if (
                    targetScope != null &&
                    targetScope in allowedScopes &&
                    isGroundedCandidate(input, candidate, targetScope)
                ) {
                    candidate to targetScope
                } else {
                    null
                }
            }
            .distinctBy { (candidate, targetScope) ->
                val dedupeKey = normalizeCanonicalKey(candidate.canonicalKey ?: candidate.slotKey)
                    ?: stableMemoryContentHash(candidate.title, candidate.body, candidate.kind, null)
                targetScope to dedupeKey
            }
        if (processedCandidates.isEmpty()) return@withLock emptyList()

        val created = mutableListOf<MemoryFact>()
        val sourceEventId = memoryService.saveRedactedSourceEvent(
            input = input,
            redactedText = input.toTurnSourceText(),
        )
        try {
            processedCandidates.forEach { (candidate, targetScope) ->
                val fact = memoryService.tryCreateCapturedFact(
                    CreateCapturedFactInput(
                        ownerId = input.context.ownerId,
                        scope = targetScope,
                        kind = candidate.kind,
                        title = candidate.title.trim(),
                        body = candidate.body.trim(),
                        canonicalKey = normalizeCanonicalKey(candidate.canonicalKey ?: candidate.slotKey),
                        confidence = candidate.confidence,
                        importance = candidate.importance,
                        evidenceText = candidate.evidenceText.trim(),
                        sourceEventId = sourceEventId,
                    )
                )
                if (fact != null) {
                    created += fact
                }
            }
            if (created.isEmpty()) cleanupSourceEvent(sourceEventId)
            created
        } catch (error: CancellationException) {
            cleanupSourceEvent(sourceEventId)
            throw error
        } catch (error: Exception) {
            cleanupSourceEvent(sourceEventId)
            throw error
        }
    }

    private suspend fun cleanupSourceEvent(sourceEventId: String) {
        try {
            withContext(NonCancellable) {
                memoryService.deleteSourceEventIfUnused(sourceEventId)
            }
        } catch (_: Exception) {
            // Best-effort cleanup; keep the original capture result or failure visible.
        }
    }

    private suspend fun extractCandidates(
        input: MemoryCaptureInput,
        explicitRemember: Boolean,
    ): List<MemoryFactCandidate> {
        val fallback = if (explicitRemember) buildExplicitRememberCandidate(input) else null
        val writerCandidates = try {
            extractWriterCandidatesWithRetry(input)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return fallback?.let(::listOf) ?: throw error.asMemoryWriterException()
        }
        return writerCandidates.ifEmpty { listOfNotNull(fallback) }
    }

    private suspend fun extractWriterCandidatesWithRetry(input: MemoryCaptureInput): List<MemoryFactCandidate> {
        var lastFailure: Exception? = null
        repeat(WRITER_ATTEMPTS) { attempt ->
            try {
                return writer.extractCandidates(input)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastFailure = error
                WRITER_RETRY_DELAYS_MS.getOrNull(attempt)?.let { delay(it) }
            }
        }
        throw lastFailure?.asMemoryWriterException() ?: MemoryWriterException("Memory writer failed")
    }

    private fun isValidCandidate(
        candidate: MemoryFactCandidate,
        explicitRemember: Boolean,
    ): Boolean {
        val threshold = if (explicitRemember) 0.4f else 0.6f
        val evidence = candidate.evidenceText.trim()
        return candidate.shouldSave &&
            candidate.title.isNotBlank() &&
            candidate.body.isNotBlank() &&
            evidence.isNotBlank() &&
            candidate.confidence in threshold..1f &&
            candidate.importance in 0f..1f &&
            !MemorySanitizer.looksSecret(candidate.title) &&
            !MemorySanitizer.looksSecret(candidate.body) &&
            !MemorySanitizer.looksSecret(evidence)
    }

    private fun isGroundedCandidate(
        input: MemoryCaptureInput,
        candidate: MemoryFactCandidate,
        targetScope: MemoryScope,
    ): Boolean {
        val evidence = candidate.evidenceText.normalizedEvidenceText()
        if (evidence.isBlank()) return false
        val allowAssistantSynthesis = candidate.kind == MemoryFactKind.EPISODE_NOTE &&
            targetScope.normalized().type == "session"
        val allowedEvidence = buildList {
            add(input.userMessage)
            input.evidence.forEach { item ->
                when (item.kind) {
                    CompletedTurnEvidenceKind.TOOL_OUTPUT -> add(item.text)
                    CompletedTurnEvidenceKind.ASSISTANT_SYNTHESIS -> if (allowAssistantSynthesis) add(item.text)
                }
            }
        }
        return allowedEvidence.any { source -> source.normalizedEvidenceText().contains(evidence) }
    }

    private fun MemoryCaptureInput.toTurnSourceText(): String {
        val sections = buildList {
            add("[USER]\n${MemorySanitizer.redact(userMessage.trim())}")
            evidence.take(MAX_TURN_EVIDENCE_SNIPPETS).forEach { item ->
                val source = item.sourceName
                    ?.let(MemorySanitizer::redact)
                    ?.replace('\n', ' ')
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?.let { " source=$it" }
                    .orEmpty()
                add("[${item.kind.name}$source]\n${MemorySanitizer.redact(item.text.trim())}")
            }
        }
        return sections.joinToString("\n\n").trimMiddle(MAX_TURN_SOURCE_CHARS)
    }

    private fun String.normalizedEvidenceText(): String =
        MemorySanitizer.redact(trim())
            .lowercase(Locale.ROOT)
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun String.trimMiddle(maxChars: Int): String {
        if (length <= maxChars) return this
        val marker = "\n...[truncated]...\n"
        val keep = (maxChars - marker.length).coerceAtLeast(0)
        val head = keep / 2
        return take(head) + marker + takeLast(keep - head)
    }

    private fun Exception.asMemoryWriterException(): MemoryWriterException =
        this as? MemoryWriterException ?: MemoryWriterException("Memory writer failed", this)

    private companion object {
        const val WRITER_ATTEMPTS = 3
        val WRITER_RETRY_DELAYS_MS = longArrayOf(250L, 1_000L)
        const val MAX_TURN_EVIDENCE_SNIPPETS = 16
        const val MAX_TURN_SOURCE_CHARS = 24_000
    }
}
