package ru.souz.ambient

class AmbientAnalysisTextParser {
    fun parse(
        blockId: String,
        raw: String,
        blockAddressedness: AmbientAddressedness,
        allowedCapabilityIds: Set<String>,
        evidenceEventIds: List<String>,
    ): AmbientAnalysisResult {
        val normalized = raw.replace("\r\n", "\n").trim()
        if (normalized.isBlank() || normalized.equals("EMPTY", ignoreCase = true)) {
            return emptyResult(blockId, raw)
        }

        val fields = normalized.lines()
            .mapNotNull { line -> line.toProtocolField() }
            .toMap()
        val rawTask = fields.firstValue("TASK", "COMMAND") ?: return emptyResult(blockId, raw)
        val rawSuggestion = fields.firstValue("SUGGEST", "SUGGESTION")
        val task = AmbientTaskTextSanitizer.normalize(rawTask, rawSuggestion)
        if (task.isBlank()) return emptyResult(blockId, raw)

        if (blockAddressedness == AmbientAddressedness.BACKGROUND_OR_QUOTED) {
            return emptyResult(blockId, raw)
        }

        val suggestion = rawSuggestion ?: "${task.take(SUGGESTION_TASK_CHARS)}?"

        val candidate = AmbientTaskCandidate(
            id = "task:$blockId:1",
            title = task.take(TITLE_CHARS),
            taskText = task,
            suggestionText = suggestion,
            confidence = TEXT_PROTOCOL_CONFIDENCE,
            addressedness = blockAddressedness,
            matchedCapabilityIds = emptyList(),
            missingSlots = emptyList(),
            risk = AmbientTaskRisk.UNKNOWN,
            requiresConfirmation = true,
            evidenceEventIds = evidenceEventIds,
            reason = "compact text protocol",
        )

        return AmbientAnalysisResult(
            blockId = blockId,
            blockSummary = null,
            extractedStatements = emptyList(),
            taskCandidates = listOf(candidate),
            rawModelOutputPreview = raw.preview(),
        )
    }

    private fun emptyResult(blockId: String, raw: String): AmbientAnalysisResult =
        AmbientAnalysisResult(
            blockId = blockId,
            blockSummary = null,
            extractedStatements = emptyList(),
            taskCandidates = emptyList(),
            rawModelOutputPreview = raw.preview(),
        )

    private fun String.toProtocolField(): Pair<String, String>? {
        val index = indexOf(':')
        if (index <= 0) return null
        val key = take(index).trim().uppercase()
        val value = drop(index + 1).trim()
        if (key !in FIELD_KEYS || value.isBlank()) return null
        return key to value
    }

    private fun Map<String, String>.firstValue(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> get(key)?.trim()?.ifBlank { null } }

    private fun String.preview(limit: Int = 500): String =
        replace(Regex("\\s+"), " ").trim().take(limit)

    private companion object {
        const val TEXT_PROTOCOL_CONFIDENCE = 1.0
        const val TITLE_CHARS = 64
        const val SUGGESTION_TASK_CHARS = 90
        val FIELD_KEYS = setOf(
            "TASK",
            "COMMAND",
            "SUGGEST",
            "SUGGESTION",
        )
    }
}
