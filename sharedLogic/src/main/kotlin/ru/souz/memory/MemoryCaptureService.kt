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
        val candidates = writer.extractCandidates(input)
        val validCandidates = candidates.filter { candidate -> isValidCandidate(candidate, isExplicitPositive) }
        if (validCandidates.isEmpty()) return emptyList()

        val redactedCombinedText = validCandidates
            .joinToString("\n---\n") { MemorySanitizer.redact(it.evidenceText) }
            .trim()

        val sourceEventId = memoryService.saveRedactedSourceEvent(input, redactedCombinedText)

        return validCandidates.map { candidate ->
            memoryService.createCapturedFact(
                CreateCapturedFactInput(
                    scope = candidate.scope ?: input.primaryScope,
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
