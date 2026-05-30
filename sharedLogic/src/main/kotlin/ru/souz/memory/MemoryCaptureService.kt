package ru.souz.memory

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MemoryCaptureService(
    private val memoryService: MemoryService,
    private val writer: MemoryWriter,
) {
    private val captureMutex = Mutex()

    suspend fun captureAfterTurn(input: MemoryCaptureInput): List<MemoryFact> = captureMutex.withLock {
        val intent = parseExplicitMemoryIntent(input.userMessage)
        if (intent == ExplicitMemoryIntent.SKIP) return emptyList()

        val isExplicitPositive = intent == ExplicitMemoryIntent.SAVE
        val primaryScope = input.primaryScope.normalized()
        val candidates = writer.extractCandidates(input)
        val validCandidates = candidates.filter { candidate -> isValidCandidate(candidate, isExplicitPositive) }
        val allowedScopes = (input.scopes + primaryScope).map { it.normalized() }.toSet()
        val processedCandidates = validCandidates.mapNotNull { candidate ->
            val targetScope = (candidate.scope ?: primaryScope).normalized()
            if (targetScope in allowedScopes) {
                candidate to targetScope
            } else {
                null
            }
        }
        if (processedCandidates.isEmpty()) return emptyList()

        val redactedCombinedText = processedCandidates
            .joinToString("\n---\n") { MemorySanitizer.redact(it.first.evidenceText) }
            .trim()

        val sourceEventId = memoryService.saveRedactedSourceEvent(input, redactedCombinedText)
        return try {
            processedCandidates.map { (candidate, targetScope) ->
                memoryService.createCapturedFact(
                    CreateCapturedFactInput(
                        scope = targetScope,
                        kind = candidate.kind,
                        title = candidate.title.trim(),
                        body = candidate.body.trim(),
                        slotKey = candidate.slotKey?.trim()?.ifBlank { null },
                        confidence = candidate.confidence,
                        evidenceText = candidate.evidenceText.trim(),
                        sourceEventId = sourceEventId,
                    )
                )
            }
        } catch (error: Exception) {
            runCatching { memoryService.deleteSourceEventIfUnused(sourceEventId) }
            throw error
        }
    }

    private fun isValidCandidate(
        candidate: MemoryFactCandidate,
        explicitRemember: Boolean,
    ): Boolean {
        val threshold = if (explicitRemember) 0.4f else 0.6f
        return candidate.shouldSave &&
            candidate.title.isNotBlank() &&
            candidate.body.isNotBlank() &&
            candidate.evidenceText.isNotBlank() &&
            candidate.confidence >= threshold
    }
}
