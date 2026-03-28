package ru.souz.tool.web.internal

internal class InternetSearchReportFormatter(
    private val support: InternetSearchSupport,
) {
    fun buildOutput(
        query: String,
        kind: InternetSearchKind,
        status: InternetSearchOutputStatus,
        answer: String,
        reportBody: String,
        results: List<InternetSearchCollectedSource>,
        sources: List<InternetSearchCollectedSource>,
        strategy: InternetSearchResearchStrategy?,
        saveLongReport: (String) -> String?,
    ): InternetSearchToolOutput {
        val localizedSourcesHeading = if (support.looksRussianText(query)) "Источники" else "Sources"
        val localizedStrategyHeading = if (support.looksRussianText(query)) "Стратегия поиска" else "Search strategy"
        val fullReportMarkdown = buildReportMarkdown(
            query = query,
            kind = kind,
            answer = answer.trim(),
            reportBody = reportBody.trim(),
            sources = sources,
            strategy = strategy,
            localizedStrategyHeading = localizedStrategyHeading,
            localizedSourcesHeading = localizedSourcesHeading,
        )
        val reportFilePath = if (
            status == InternetSearchOutputStatus.COMPLETE &&
            kind == InternetSearchKind.RESEARCH &&
            fullReportMarkdown.length >= support.maxInlineReportChars
        ) {
            saveLongReport(fullReportMarkdown)
        } else {
            null
        }
        val finalAnswer = if (reportFilePath != null) {
            appendReportFileNote(answer.trim(), reportFilePath, query)
        } else {
            answer.trim()
        }
        val finalReportMarkdown = if (reportFilePath != null) {
            buildInlineReportPreview(
                query = query,
                answer = answer.trim(),
                reportFilePath = reportFilePath,
                sources = sources,
                strategy = strategy,
                localizedStrategyHeading = localizedStrategyHeading,
                localizedSourcesHeading = localizedSourcesHeading,
            )
        } else {
            fullReportMarkdown
        }

        return InternetSearchToolOutput(
            status = status.name,
            query = query,
            answer = finalAnswer,
            reportMarkdown = finalReportMarkdown,
            reportFilePath = reportFilePath,
            results = results.toOutputSources(),
            sources = sources.toOutputSources(),
            strategy = strategy?.let {
                InternetSearchToolOutputStrategy(
                    goal = it.goal,
                    searchQueries = it.searchQueries,
                    subQuestions = it.subQuestions,
                    answerSections = it.answerSections,
                )
            },
        )
    }

    private fun buildReportMarkdown(
        query: String,
        kind: InternetSearchKind,
        answer: String,
        reportBody: String,
        sources: List<InternetSearchCollectedSource>,
        strategy: InternetSearchResearchStrategy?,
        localizedStrategyHeading: String,
        localizedSourcesHeading: String,
    ): String = buildString {
        if (kind == InternetSearchKind.RESEARCH) {
            appendLine("# ${query.trim()}")
            appendLine()
            appendLine("## ${if (support.looksRussianText(query)) "Краткий вывод" else "Executive summary"}")
            appendLine(answer)
            if (reportBody.isNotBlank() && reportBody != answer) {
                appendLine()
                appendLine("## ${if (support.looksRussianText(query)) "Подробный отчёт" else "Detailed report"}")
                appendLine(reportBody)
            }
        } else {
            append(answer)
        }
        if (strategy != null) {
            appendLine()
            appendLine()
            appendLine("## $localizedStrategyHeading")
            strategy.searchQueries.forEach { appendLine("- $it") }
        }
        if (sources.isNotEmpty()) {
            appendLine()
            appendLine()
            appendLine("## $localizedSourcesHeading")
            sources.forEach { source ->
                appendLine("[${source.index}] ${source.title} - ${source.url}")
            }
        }
    }.trim()

    private fun buildInlineReportPreview(
        query: String,
        answer: String,
        reportFilePath: String,
        sources: List<InternetSearchCollectedSource>,
        strategy: InternetSearchResearchStrategy?,
        localizedStrategyHeading: String,
        localizedSourcesHeading: String,
    ): String = buildString {
        append(answer.trim())
        appendLine()
        appendLine()
        appendLine(
            if (support.looksRussianText(query)) {
                "Полный отчёт сохранён в файл: `$reportFilePath`"
            } else {
                "Full report was saved to: `$reportFilePath`"
            }
        )
        if (strategy != null) {
            appendLine()
            appendLine()
            appendLine("$localizedStrategyHeading:")
            strategy.searchQueries.forEach { appendLine("- $it") }
        }
        if (sources.isNotEmpty()) {
            appendLine()
            appendLine()
            appendLine("$localizedSourcesHeading:")
            sources.forEach { source ->
                appendLine("[${source.index}] ${source.title} - ${source.url}")
            }
        }
    }.trim()

    private fun appendReportFileNote(
        answer: String,
        reportFilePath: String,
        query: String,
    ): String {
        val note = if (support.looksRussianText(query)) {
            "Полный отчёт сохранён в `$reportFilePath`."
        } else {
            "The full report was saved to `$reportFilePath`."
        }
        return buildString {
            append(answer.trim())
            appendLine()
            appendLine()
            append(note)
        }.trim()
    }

    private fun List<InternetSearchCollectedSource>.toOutputSources(): List<InternetSearchToolOutputSource> =
        map { source ->
            InternetSearchToolOutputSource(
                index = source.index,
                title = source.title,
                url = source.url,
                foundByQuery = source.foundByQuery,
                snippet = source.snippet,
            )
        }
}
