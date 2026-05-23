package ru.souz.memory

class MemoryCaptureService(
    private val memoryService: MemoryService,
    private val writer: MemoryWriter,
) {
    suspend fun captureAfterTurn(input: MemoryCaptureInput): List<MemoryFact> {
        val sourceEventId = memoryService.saveTurnSourceEvent(input)
        val explicitRemember = hasExplicitRememberIntent(input.userMessage)
        return writer.extractCandidates(input)
            .filter { candidate -> isValidCandidate(candidate, explicitRemember) }
            .map { candidate ->
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
