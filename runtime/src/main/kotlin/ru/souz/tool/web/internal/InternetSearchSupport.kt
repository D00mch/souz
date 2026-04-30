package ru.souz.tool.web.internal

enum class InternetSearchKind {
    QUICK,
    RESEARCH,
}

enum class InternetSearchOutputStatus {
    COMPLETE,
    PARTIAL,
    NO_RESULTS,
    PROVIDER_BLOCKED,
    PROVIDER_UNAVAILABLE,
}

data class InternetSearchToolOutput(
    val status: String,
    val query: String,
    val answer: String,
    val reportMarkdown: String,
    val reportFilePath: String?,
    val results: List<InternetSearchToolOutputSource>,
    val sources: List<InternetSearchToolOutputSource>,
    val strategy: InternetSearchToolOutputStrategy?,
)

data class InternetSearchToolOutputSource(
    val index: Int,
    val title: String,
    val url: String,
    val foundByQuery: String,
    val snippet: String,
)

data class InternetSearchToolOutputStrategy(
    val goal: String,
    val searchQueries: List<String>,
    val subQuestions: List<String>,
    val answerSections: List<String>,
)

data class InternetSearchResearchStrategy(
    val goal: String,
    val searchQueries: List<String>,
    val subQuestions: List<String>,
    val answerSections: List<String>,
)

data class InternetSearchStrategyDraft(
    val goal: String? = null,
    val searchQueries: List<String> = emptyList(),
    val subQuestions: List<String> = emptyList(),
    val answerSections: List<String> = emptyList(),
)

data class InternetSearchSynthesisDraft(
    val answer: String? = null,
    val reportMarkdown: String? = null,
    val usedSourceIndexes: List<Int> = emptyList(),
)

data class InternetSearchCollectedSource(
    val index: Int,
    val title: String,
    val url: String,
    val snippet: String,
    val foundByQuery: String,
    val pageText: String?,
)

data class InternetSearchCollectionResult(
    val sources: List<InternetSearchCollectedSource>,
    val providerStatus: InternetSearchOutputStatus? = null,
)

data class InternetSearchSynthesisResult(
    val status: InternetSearchOutputStatus,
    val draft: InternetSearchSynthesisDraft,
)

data class InternetSearchPromptSpec(
    val systemPrompt: String,
    val rescueSystemPrompt: String,
    val maxTokens: Int,
    val rescueMaxTokens: Int,
)

class InternetSearchSupport {
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

    fun looksRussianText(text: String): Boolean = text.any { it in '\u0400'..'\u04FF' }

    fun buildFallbackDraft(
        kind: InternetSearchKind,
        query: String,
        sources: List<InternetSearchCollectedSource>,
    ): InternetSearchSynthesisDraft {
        val isRussianQuery = looksRussianText(query)
        val message = when (kind) {
            InternetSearchKind.QUICK ->
                if (isRussianQuery) {
                    "Не удалось надёжно синтезировать короткий ответ. Ниже ключевые найденные источники."
                } else {
                    "Unable to synthesize a reliable short answer. Key sources are listed below."
                }

            InternetSearchKind.RESEARCH ->
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
        status: InternetSearchOutputStatus,
    ): String = when (status) {
        InternetSearchOutputStatus.PROVIDER_BLOCKED ->
            if (looksRussianText(query)) {
                "Интернет-поиск временно недоступен: поисковый провайдер заблокировал автоматические запросы. Это не означает, что по теме нет источников."
            } else {
                "Internet search is temporarily unavailable because the search provider blocked automated requests. This does not mean the topic has no sources."
            }

        InternetSearchOutputStatus.PROVIDER_UNAVAILABLE ->
            if (looksRussianText(query)) {
                "Интернет-поиск временно недоступен: поисковый провайдер не отвечает или возвращает ошибки. Это не означает, что по теме нет источников."
            } else {
                "Internet search is temporarily unavailable because the search provider is timing out or returning errors. This does not mean the topic has no sources."
            }

        InternetSearchOutputStatus.NO_RESULTS ->
            if (looksRussianText(query)) {
                "Не удалось найти релевантные интернет-источники по этому запросу."
            } else {
                "No relevant internet sources were found for this request."
            }

        else -> error("Unexpected empty-source status: $status")
    }
}
