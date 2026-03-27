package ru.souz.tool.web.internal

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

internal class InternetSearchDraftParser(
    private val mapper: ObjectMapper,
) {
    fun readStrategyDraft(raw: String): InternetSearchStrategyDraft? = readJsonOrNull(raw)

    fun recoverSynthesisDraft(
        raw: String,
        kind: InternetSearchKind,
        sources: List<InternetSearchCollectedSource>,
    ): InternetSearchSynthesisDraft? {
        val draft = readJsonOrNull<InternetSearchSynthesisDraft>(raw)?.normalize(kind)
            ?: normalizeFreeformDraft(raw, kind, sources)
            ?: return null
        return finalizeSynthesisDraft(draft, sources)
    }

    fun isGrounded(draft: InternetSearchSynthesisDraft): Boolean =
        !draft.answer.isNullOrBlank() && draft.usedSourceIndexes.isNotEmpty()

    private fun normalizeFreeformDraft(
        raw: String,
        kind: InternetSearchKind,
        sources: List<InternetSearchCollectedSource>,
    ): InternetSearchSynthesisDraft? {
        val cleaned = stripCodeFences(raw).trim()
        if (cleaned.isBlank()) return null

        return when (kind) {
            InternetSearchKind.QUICK -> InternetSearchSynthesisDraft(
                answer = cleaned,
                reportMarkdown = cleaned,
                usedSourceIndexes = parseInlineSourceIndexes(cleaned, sources),
            )

            InternetSearchKind.RESEARCH -> {
                val paragraphs = cleaned
                    .split(Regex("\\n\\s*\\n"))
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                val looksLikeReport = cleaned.contains("\n## ") ||
                    cleaned.contains("\n# ") ||
                    paragraphs.size >= 3
                if (!looksLikeReport && cleaned.length < MIN_RESEARCH_FREEFORM_CHARS) {
                    return null
                }
                InternetSearchSynthesisDraft(
                    answer = extractExecutiveSummary(cleaned),
                    reportMarkdown = cleaned,
                    usedSourceIndexes = parseInlineSourceIndexes(cleaned, sources),
                )
            }
        }
    }

    private fun finalizeSynthesisDraft(
        draft: InternetSearchSynthesisDraft,
        sources: List<InternetSearchCollectedSource>,
    ): InternetSearchSynthesisDraft {
        val inlineIndexes = buildList {
            draft.answer?.let { addAll(parseInlineSourceIndexes(it, sources)) }
            draft.reportMarkdown?.let { addAll(parseInlineSourceIndexes(it, sources)) }
        }
        return draft.copy(
            answer = draft.answer?.trim(),
            reportMarkdown = draft.reportMarkdown?.trim(),
            usedSourceIndexes = sanitizeIndexes(draft.usedSourceIndexes + inlineIndexes, sources),
        )
    }

    private fun parseInlineSourceIndexes(
        text: String,
        sources: List<InternetSearchCollectedSource>,
    ): List<Int> {
        val allowed = sources.map { it.index }.toSet()
        return Regex("\\[(\\d+)]")
            .findAll(text)
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .filter { it in allowed }
            .distinct()
            .toList()
    }

    private fun sanitizeIndexes(
        indexes: List<Int>,
        sources: List<InternetSearchCollectedSource>,
    ): List<Int> {
        val allowed = sources.map { it.index }.toSet()
        return indexes.filter { it in allowed }.distinct()
    }

    private fun extractExecutiveSummary(reportMarkdown: String): String {
        val paragraphs = reportMarkdown
            .split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
        return paragraphs
            .take(2)
            .joinToString(separator = "\n\n")
            .take(MAX_EXECUTIVE_SUMMARY_CHARS)
            .ifBlank { reportMarkdown.trim().take(MAX_EXECUTIVE_SUMMARY_CHARS) }
    }

    private fun InternetSearchSynthesisDraft.normalize(
        kind: InternetSearchKind,
    ): InternetSearchSynthesisDraft? {
        val normalizedAnswer = answer?.trim().takeIf { !it.isNullOrBlank() }
        val normalizedReport = reportMarkdown?.trim().takeIf { !it.isNullOrBlank() }
        val resolvedAnswer = normalizedAnswer ?: when (kind) {
            InternetSearchKind.QUICK -> normalizedReport
            InternetSearchKind.RESEARCH -> normalizedReport?.let(::extractExecutiveSummary)
        }
        val resolvedReport = normalizedReport ?: resolvedAnswer
        if (resolvedAnswer.isNullOrBlank() && resolvedReport.isNullOrBlank()) return null
        return copy(answer = resolvedAnswer, reportMarkdown = resolvedReport)
    }

    private inline fun <reified T> readJsonOrNull(raw: String): T? {
        val stripped = stripCodeFences(raw)
        return runCatching { mapper.readValue<T>(stripped) }.getOrNull()
            ?: runCatching {
                val start = stripped.indexOf('{')
                val end = stripped.lastIndexOf('}')
                if (start >= 0 && end > start) {
                    mapper.readValue<T>(stripped.substring(start, end + 1))
                } else {
                    null
                }
            }.getOrNull()
    }

    private fun stripCodeFences(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("```")) return trimmed
        return trimmed
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    }

    companion object {
        private const val MAX_EXECUTIVE_SUMMARY_CHARS = 1_600
        private const val MIN_RESEARCH_FREEFORM_CHARS = 280
    }
}
