package ru.souz.tool.web

internal fun buildInternetSearchOutput(
    query: String,
    mode: ToolInternetSearch.SearchMode,
    status: ToolInternetSearch.OutputStatus,
    answer: String,
    reportBody: String,
    sources: List<InternetSearchCollectedSource>,
    strategy: InternetSearchResearchStrategy?,
    saveLongReport: (String) -> String?,
): ToolInternetSearch.Output {
    val localizedSourcesHeading = if (looksRussianText(query)) "Источники" else "Sources"
    val localizedStrategyHeading = if (looksRussianText(query)) "Стратегия поиска" else "Search strategy"
    val fullReportMarkdown = buildInternetSearchReportMarkdown(
        query = query,
        mode = mode,
        answer = answer.trim(),
        reportBody = reportBody.trim(),
        sources = sources,
        strategy = strategy,
        localizedStrategyHeading = localizedStrategyHeading,
        localizedSourcesHeading = localizedSourcesHeading,
    )
    val reportFilePath = if (
        status == ToolInternetSearch.OutputStatus.COMPLETE &&
        mode == ToolInternetSearch.SearchMode.RESEARCH &&
        fullReportMarkdown.length >= INTERNET_SEARCH_MAX_INLINE_REPORT_CHARS
    ) {
        saveLongReport(fullReportMarkdown)
    } else {
        null
    }
    val finalAnswer = if (reportFilePath != null) {
        appendInternetSearchReportFileNote(answer.trim(), reportFilePath, query)
    } else {
        answer.trim()
    }
    val finalReportMarkdown = if (reportFilePath != null) {
        buildInternetSearchInlineReportPreview(
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

    return ToolInternetSearch.Output(
        status = status.name,
        mode = mode.name,
        query = query,
        answer = finalAnswer,
        reportMarkdown = finalReportMarkdown,
        reportFilePath = reportFilePath,
        sources = sources.map { source ->
            ToolInternetSearch.OutputSource(
                index = source.index,
                title = source.title,
                url = source.url,
                foundByQuery = source.foundByQuery,
                snippet = source.snippet,
            )
        },
        strategy = strategy?.let {
            ToolInternetSearch.OutputStrategy(
                goal = it.goal,
                searchQueries = it.searchQueries,
                subQuestions = it.subQuestions,
                answerSections = it.answerSections,
            )
        },
    )
}

private fun buildInternetSearchReportMarkdown(
    query: String,
    mode: ToolInternetSearch.SearchMode,
    answer: String,
    reportBody: String,
    sources: List<InternetSearchCollectedSource>,
    strategy: InternetSearchResearchStrategy?,
    localizedStrategyHeading: String,
    localizedSourcesHeading: String,
): String = buildString {
    if (mode == ToolInternetSearch.SearchMode.RESEARCH) {
        appendLine("# ${query.trim()}")
        appendLine()
        appendLine("## ${if (looksRussianText(query)) "Краткий вывод" else "Executive summary"}")
        appendLine(answer)
        if (reportBody.isNotBlank() && reportBody != answer) {
            appendLine()
            appendLine("## ${if (looksRussianText(query)) "Подробный отчёт" else "Detailed report"}")
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

private fun buildInternetSearchInlineReportPreview(
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
        if (looksRussianText(query)) {
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

private fun appendInternetSearchReportFileNote(
    answer: String,
    reportFilePath: String,
    query: String,
): String {
    val note = if (looksRussianText(query)) {
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
