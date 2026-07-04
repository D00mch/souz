package ru.souz.memory

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
        val validCandidates = candidates.filter { candidate -> isValidCandidate(candidate, isExplicitPositive) }
        val allowedScopes = (input.scopes + input.context.allowedRetrievalScopes(includeChat = input.context.surface == MemorySurface.BACKEND))
            .map { it.normalized() }
            .toSet()
        val processedCandidates = validCandidates
            .mapNotNull { candidate ->
                val legacyScope = candidate.scope?.normalized()?.takeIf { it in allowedScopes }
                val targetScope = legacyScope
                    ?: input.context.resolveRequestedScope(candidate.requestedScope, candidate.kind)?.normalized()
                if (targetScope != null && targetScope in allowedScopes) {
                    candidate to targetScope
                } else {
                    null
                }
            }
            .distinctBy { (candidate, _) ->
                normalizeCanonicalKey(candidate.canonicalKey ?: candidate.slotKey)
                    ?: stableMemoryContentHash(candidate.title, candidate.body, candidate.kind, null)
            }
        if (processedCandidates.isEmpty()) return@withLock emptyList()

        val redactedCombinedText = processedCandidates
            .joinToString("\n---\n") { MemorySanitizer.redact(it.first.evidenceText) }
            .trim()

        val sourceEventId = memoryService.saveRedactedSourceEvent(input, redactedCombinedText)
        try {
            processedCandidates.mapNotNull { (candidate, targetScope) ->
                memoryService.tryCreateCapturedFact(
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
            }.also { created ->
                if (created.isEmpty()) {
                    memoryService.deleteSourceEventIfUnused(sourceEventId)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            try {
                memoryService.deleteSourceEventIfUnused(sourceEventId)
            } catch (cleanupCancellation: CancellationException) {
                throw cleanupCancellation
            } catch (_: Exception) {
                // Best-effort cleanup; keep the original capture failure visible.
            }
            throw error
        }
    }

    private suspend fun extractCandidates(
        input: MemoryCaptureInput,
        explicitRemember: Boolean,
    ): List<MemoryFactCandidate> {
        val fallback = if (explicitRemember) buildExplicitRememberCandidate(input) else null
        val writerCandidates = try {
            writer.extractCandidates(input)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
        return listOfNotNull(fallback) + writerCandidates
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
}
