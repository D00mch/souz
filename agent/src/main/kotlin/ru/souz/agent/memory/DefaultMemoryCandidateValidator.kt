package ru.souz.agent.memory

private val obviousEphemeralPhrases = listOf(
    "hello",
    "hi",
    "hey",
    "thanks",
    "thank you",
    "bye",
    "good morning",
    "good afternoon",
    "good evening",
    "how are you",
    "nice to meet you",
    "chit-chat",
    "small talk",
    "greeting",
)

class DefaultMemoryCandidateValidator(
    private val confidenceThreshold: Double = 0.75,
    private val perTurnAcceptedLimit: Int = 5,
) {
    fun validate(input: MemoryCandidateValidationInput): MemoryCandidateValidationResult {
        val candidate = input.candidate
        val evidenceRefs = candidate.evidenceRefs.mapNotNull(::normalizeText)
        if (evidenceRefs.isEmpty()) {
            return rejected(MemoryCandidateRejectionReason.NO_EVIDENCE_REFS)
        }

        val resolvedEvidence = evidenceRefs.mapNotNull(input.evidenceIndex::get)
        if (resolvedEvidence.isEmpty()) {
            return rejected(MemoryCandidateRejectionReason.NO_EVIDENCE_REFS)
        }
        if (resolvedEvidence.all { it.evidenceType == MemoryEvidenceType.ASSISTANT_MESSAGE }) {
            return rejected(MemoryCandidateRejectionReason.ASSISTANT_ONLY_EVIDENCE)
        }
        if (normalizeKey(candidate.predicate).isNullOrBlank()) {
            return rejected(MemoryCandidateRejectionReason.BLANK_PREDICATE)
        }
        if (candidate.confidence < confidenceThreshold) {
            return rejected(MemoryCandidateRejectionReason.LOW_CONFIDENCE)
        }
        if (candidate.scope.id.isBlank() || (input.allowedScopes.isNotEmpty() && candidate.scope !in input.allowedScopes)) {
            return rejected(MemoryCandidateRejectionReason.INVALID_SCOPE)
        }
        if (candidate.suggestedStatus != MemoryFactStatus.ACTIVE) {
            return rejected(MemoryCandidateRejectionReason.UNSUPPORTED_STATUS)
        }
        if (candidate.conflictPolicy == MemoryConflictPolicy.SINGLE_ACTIVE_PER_SLOT && candidate.slotKey.isNullOrBlank()) {
            return rejected(MemoryCandidateRejectionReason.BLANK_CONFLICT_SLOT_KEY)
        }
        if (candidate.isObviouslyEphemeral()) {
            return rejected(MemoryCandidateRejectionReason.EPHEMERAL)
        }
        if (input.activeFacts.any { it.status == MemoryFactStatus.ACTIVE && it.duplicateFingerprint() == candidate.duplicateFingerprint() }) {
            return rejected(MemoryCandidateRejectionReason.DUPLICATE_ACTIVE_FACT)
        }
        if (input.acceptedCountThisTurn >= perTurnAcceptedLimit) {
            return rejected(MemoryCandidateRejectionReason.TURN_LIMIT_EXCEEDED)
        }
        return MemoryCandidateValidationResult(accepted = true)
    }

    private fun rejected(reason: MemoryCandidateRejectionReason): MemoryCandidateValidationResult =
        MemoryCandidateValidationResult(accepted = false, rejectionReason = reason)

    private fun MemoryCandidate.isObviouslyEphemeral(): Boolean {
        val text = listOfNotNull(
            normalizeText(objectValueText),
            normalizeText(objectEntityCanonicalName),
            normalizeText(reasonToStore),
        ).joinToString(" ").lowercase()
        return obviousEphemeralPhrases.any { phrase -> text.contains(phrase) }
    }
}
