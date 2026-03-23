package ru.souz.tool.web.internal

import ru.souz.tool.web.ToolInternetSearch

internal data class InternetSearchResearchStrategy(
    val goal: String,
    val searchQueries: List<String>,
    val subQuestions: List<String>,
    val answerSections: List<String>,
)

internal data class InternetSearchStrategyDraft(
    val goal: String? = null,
    val searchQueries: List<String> = emptyList(),
    val subQuestions: List<String> = emptyList(),
    val answerSections: List<String> = emptyList(),
)

internal data class InternetSearchSynthesisDraft(
    val answer: String? = null,
    val reportMarkdown: String? = null,
    val usedSourceIndexes: List<Int> = emptyList(),
)

internal data class InternetSearchCollectedSource(
    val index: Int,
    val title: String,
    val url: String,
    val snippet: String,
    val foundByQuery: String,
    val pageText: String?,
)

internal data class InternetSearchCollectionResult(
    val sources: List<InternetSearchCollectedSource>,
    val providerStatus: ToolInternetSearch.OutputStatus? = null,
)

internal data class InternetSearchSynthesisResult(
    val status: ToolInternetSearch.OutputStatus,
    val draft: InternetSearchSynthesisDraft,
)

internal data class InternetSearchPromptSpec(
    val systemPrompt: String,
    val rescueSystemPrompt: String,
    val maxTokens: Int,
    val rescueMaxTokens: Int,
)

internal class InternetSearchSupport {
    val maxSearchQueries: Int = 6
    val maxResearchSources: Int = 16
    val researchResultsPerQuery: Int = 8
    val quickPageTextLimit: Int = 3_500
    val researchPageTextLimit: Int = 10_500
    val maxInlineReportChars: Int = 8_000

    fun fallbackResearchStrategy(query: String): InternetSearchResearchStrategy {
        val suffixes = if (looksRussianText(query)) {
            listOf("обзор", "сравнение", "официальные источники")
        } else {
            listOf("overview", "comparison", "official sources")
        }
        return InternetSearchResearchStrategy(
            goal = query,
            searchQueries = buildList {
                add(query)
                addAll(suffixes.map { "$query $it" })
            }.distinct().take(maxSearchQueries),
            subQuestions = emptyList(),
            answerSections = emptyList(),
        )
    }

    fun sanitizeSearchStrings(
        values: List<String>,
        fallback: List<String>,
        minItems: Int,
        maxItems: Int,
    ): List<String> {
        val cleaned = values.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(maxItems)
        return if (cleaned.size >= minItems) cleaned else fallback.take(maxItems)
    }

    fun selectUsedSources(
        sources: List<InternetSearchCollectedSource>,
        usedIndexes: List<Int>,
    ): List<InternetSearchCollectedSource> {
        if (usedIndexes.isEmpty()) return emptyList()
        return sources.filter { it.index in usedIndexes }
    }

    fun inferMode(query: String): ToolInternetSearch.SearchMode {
        val normalized = query.lowercase()
        val researchSignals = listOf(
            "исслед",
            "ресерч",
            "research",
            "обзор",
            "сравн",
            "подбери",
            "подходящ",
            "выбери",
            "library",
            "framework",
            "tooling",
            "рынок",
            "тренд",
            "анализ",
            "проанализ",
        )
        if (researchSignals.any { normalized.contains(it) }) return ToolInternetSearch.SearchMode.RESEARCH

        val quickSignals = listOf(
            "погод",
            "weather",
            "какая",
            "какой",
            "какое",
            "кто",
            "что",
            "сколько",
            "где",
            "когда",
        )
        if (quickSignals.any { normalized.startsWith(it) || normalized.contains(" $it") }) {
            return ToolInternetSearch.SearchMode.QUICK_ANSWER
        }

        return if (query.length <= 80 && '?' in query) {
            ToolInternetSearch.SearchMode.QUICK_ANSWER
        } else {
            ToolInternetSearch.SearchMode.RESEARCH
        }
    }

    fun looksRussianText(text: String): Boolean = text.any { it in '\u0400'..'\u04FF' }

    fun buildFallbackDraft(
        mode: ToolInternetSearch.SearchMode,
        query: String,
        sources: List<InternetSearchCollectedSource>,
    ): InternetSearchSynthesisDraft {
        val isRussianQuery = looksRussianText(query)
        val message = when (mode) {
            ToolInternetSearch.SearchMode.QUICK_ANSWER ->
                if (isRussianQuery) {
                    "Не удалось надёжно синтезировать короткий ответ. Ниже ключевые найденные источники."
                } else {
                    "Unable to synthesize a reliable short answer. Key sources are listed below."
                }

            ToolInternetSearch.SearchMode.RESEARCH ->
                if (isRussianQuery) {
                    "Не удалось собрать надёжный финальный ресерч-ответ. Ниже черновой digest по найденным источникам для ручной проверки."
                } else {
                    "Unable to assemble a reliable final research answer. A draft digest of the sources is listed below for manual review."
                }
        }
        val noPreviewMessage = if (isRussianQuery) "Нет краткого превью." else "No preview available."
        val preview = sources.joinToString(separator = "\n") { source ->
            val detail = source.snippet.ifBlank { source.pageText?.take(220).orEmpty() }.ifBlank { noPreviewMessage }
            "[${source.index}] ${source.title}: $detail"
        }
        return InternetSearchSynthesisDraft(
            answer = "$message\n\n$preview",
            reportMarkdown = "$message\n\n$preview",
            usedSourceIndexes = sources.map { it.index },
        )
    }

    fun buildEmptySourcesMessage(
        query: String,
        status: ToolInternetSearch.OutputStatus,
    ): String = when (status) {
        ToolInternetSearch.OutputStatus.PROVIDER_BLOCKED ->
            if (looksRussianText(query)) {
                "Интернет-поиск временно недоступен: поисковый провайдер заблокировал автоматические запросы. Это не означает, что по теме нет источников."
            } else {
                "Internet search is temporarily unavailable because the search provider blocked automated requests. This does not mean the topic has no sources."
            }

        ToolInternetSearch.OutputStatus.PROVIDER_UNAVAILABLE ->
            if (looksRussianText(query)) {
                "Интернет-поиск временно недоступен: поисковый провайдер не отвечает или возвращает ошибки. Это не означает, что по теме нет источников."
            } else {
                "Internet search is temporarily unavailable because the search provider is timing out or returning errors. This does not mean the topic has no sources."
            }

        ToolInternetSearch.OutputStatus.NO_RESULTS ->
            if (looksRussianText(query)) {
                "Не удалось найти релевантные интернет-источники по этому запросу."
            } else {
                "No relevant internet sources were found for this request."
            }

        else -> error("Unexpected empty-source status: $status")
    }
}
