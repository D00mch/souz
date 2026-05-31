package ru.souz.ambient

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

class AmbientAnalysisJsonParser {
    private val mapper = jacksonObjectMapper()

    fun parse(
        blockId: String,
        raw: String,
        blockAddressedness: AmbientAddressedness,
        allowedCapabilityIds: Set<String>,
        evidenceEventIds: List<String>,
    ): AmbientAnalysisResult {
        val json = raw.extractFirstJsonObject()
            ?: return emptyResult(blockId, raw)
        val root = runCatching { mapper.readTree(json) }
            .getOrElse { return emptyResult(blockId, raw) }

        val statements = root.path("statements")
            .takeIf { it.isArray }
            ?.mapIndexedNotNull { index, node ->
                val text = node.path("text").asText("").trim()
                if (text.isBlank()) return@mapIndexedNotNull null
                AmbientExtractedStatement(
                    id = node.path("id").asText("").ifBlank { "statement:$blockId:${index + 1}" },
                    text = text,
                    kind = node.path("kind").asText("").toEnumOrDefault(AmbientStatementKind.OTHER),
                    confidence = node.path("confidence").asDouble(0.0).clampConfidence(),
                    evidenceEventIds = node.safeEvidenceIds(evidenceEventIds),
                )
            }
            .orEmpty()

        val candidates = root.path("task_candidates")
            .takeIf { it.isArray }
            ?.mapIndexedNotNull { index, node ->
                val addressedness = node.path("addressedness")
                    .asText("")
                    .toEnumOrDefault(blockAddressedness)
                if (
                    addressedness == AmbientAddressedness.BACKGROUND_OR_QUOTED ||
                    blockAddressedness == AmbientAddressedness.BACKGROUND_OR_QUOTED
                ) {
                    return@mapIndexedNotNull null
                }

                val title = node.path("title").asText("").trim()
                val taskText = node.path("task_text").asText("").trim()
                val suggestionText = node.path("suggestion_text").asText("").trim()
                if (title.isBlank() || taskText.isBlank() || suggestionText.isBlank()) {
                    return@mapIndexedNotNull null
                }

                AmbientTaskCandidate(
                    id = node.path("id").asText("").ifBlank { "task:$blockId:${index + 1}" },
                    title = title,
                    taskText = taskText,
                    suggestionText = suggestionText,
                    confidence = node.path("confidence").asDouble(0.0).clampConfidence(),
                    addressedness = addressedness,
                    matchedCapabilityIds = node.path("matched_capability_ids")
                        .stringArray()
                        .filter { it in allowedCapabilityIds },
                    missingSlots = node.path("missing_slots").stringArray(),
                    risk = node.path("risk").asText("").toEnumOrDefault(AmbientTaskRisk.UNKNOWN),
                    requiresConfirmation = true,
                    evidenceEventIds = node.safeEvidenceIds(evidenceEventIds),
                    reason = node.path("reason").asText("").trim(),
                )
            }
            .orEmpty()

        return AmbientAnalysisResult(
            blockId = blockId,
            blockSummary = root.path("block_summary").asText("").trim().ifBlank { null },
            extractedStatements = statements,
            taskCandidates = candidates,
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

    private fun JsonNode.safeEvidenceIds(fallback: List<String>): List<String> {
        val ids = path("evidence_event_ids").stringArray()
        return ids.filter { it in fallback }.ifEmpty { fallback }
    }

    private fun JsonNode.stringArray(): List<String> =
        takeIf { it.isArray }
            ?.mapNotNull { node -> node.asText("").trim().ifBlank { null } }
            .orEmpty()

    private inline fun <reified T : Enum<T>> String.toEnumOrDefault(default: T): T =
        runCatching { enumValueOf<T>(this) }.getOrDefault(default)

    private fun Double.clampConfidence(): Double = coerceIn(0.0, 1.0)

    private fun String.preview(limit: Int = 500): String = replace(Regex("\\s+"), " ").trim().take(limit)

    private fun String.extractFirstJsonObject(): String? {
        val start = indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until length) {
            val char = this[index]
            if (escaped) {
                escaped = false
                continue
            }
            if (inString && char == '\\') {
                escaped = true
                continue
            }
            if (char == '"') {
                inString = !inString
                continue
            }
            if (inString) continue
            when (char) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return substring(start, index + 1)
                }
            }
        }
        return null
    }
}
