package ru.souz.tool.presentation

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import ru.souz.db.SettingsProvider
import ru.souz.giga.GigaChatAPI
import ru.souz.giga.GigaMessageRole
import ru.souz.giga.GigaRequest
import ru.souz.giga.GigaResponse
import ru.souz.giga.gigaJsonMapper
import ru.souz.tool.BadInputException
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolSetup
import ru.souz.tool.files.FilesToolUtil
import java.io.File
import java.nio.charset.StandardCharsets

class ToolInternetSearch(
    private val api: GigaChatAPI,
    private val settingsProvider: SettingsProvider,
    private val webResearchClient: WebResearchClient,
    private val filesToolUtil: FilesToolUtil,
    private val mapper: ObjectMapper = gigaJsonMapper,
) : ToolSetup<ToolInternetSearch.Input> {
    private val l = LoggerFactory.getLogger(ToolInternetSearch::class.java)
    private val tolerantMapper: ObjectMapper = mapper.copy()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    enum class OutputStatus {
        COMPLETE,
        PARTIAL,
        NO_RESULTS,
    }

    enum class SearchMode {
        QUICK_ANSWER,
        RESEARCH,
    }

    data class Input(
        @InputParamDescription("User's internet search request")
        val query: String,
        @InputParamDescription("Optional mode. QUICK_ANSWER for one direct factual answer. RESEARCH for multi-source research, comparison, tool/library selection, or thematic review.")
        val mode: SearchMode? = null,
        @InputParamDescription("Maximum number of source pages to study (1..16). QUICK_ANSWER usually needs 1..3, RESEARCH should usually use 10..16.")
        val maxSources: Int = 10,
    )

    data class Output(
        val status: String,
        val mode: String,
        val query: String,
        val answer: String,
        val reportMarkdown: String,
        val reportFilePath: String?,
        val sources: List<OutputSource>,
        val strategy: OutputStrategy?,
    )

    data class OutputSource(
        val index: Int,
        val title: String,
        val url: String,
        val foundByQuery: String,
        val snippet: String,
    )

    data class OutputStrategy(
        val goal: String,
        val searchQueries: List<String>,
        val subQuestions: List<String>,
        val answerSections: List<String>,
    )

    private data class ResearchStrategy(
        val goal: String,
        val searchQueries: List<String>,
        val subQuestions: List<String>,
        val answerSections: List<String>,
    )

    private data class StrategyDraft(
        val goal: String? = null,
        val searchQueries: List<String> = emptyList(),
        val subQuestions: List<String> = emptyList(),
        val answerSections: List<String> = emptyList(),
    )

    private data class SynthesisDraft(
        val answer: String? = null,
        val reportMarkdown: String? = null,
        val usedSourceIndexes: List<Int> = emptyList(),
    )

    private data class CollectedSource(
        val index: Int,
        val title: String,
        val url: String,
        val snippet: String,
        val foundByQuery: String,
        val pageText: String?,
    )

    private data class SynthesisResult(
        val status: OutputStatus,
        val draft: SynthesisDraft,
    )

    override val name: String = "InternetSearch"
    override val description: String =
        "High-level internet search with two modes. Use QUICK_ANSWER for a simple web fact like weather, current status, or one direct answer. " +
            "Use RESEARCH for multi-step research, comparisons, library/tool selection, market overviews, or thematic analysis. " +
            "In RESEARCH mode the tool builds a search strategy, runs multiple queries, studies sources, and returns a synthesized answer with sources. " +
            "Interpret `status` carefully: COMPLETE means the output is ready for user delivery; PARTIAL means the tool found sources but failed to produce a reliable final synthesis, so do not invent missing facts; NO_RESULTS means usable sources were not found. " +
            "`reportFilePath` is present only when a COMPLETE long-form report was exported to a .md file."

    override val fewShotExamples: List<FewShotExample> = listOf(
        FewShotExample(
            request = "Какая погода в Таллине",
            params = mapOf("query" to "Какая погода в Таллине", "mode" to SearchMode.QUICK_ANSWER.name)
        ),
        FewShotExample(
            request = "Проведи исследование про ИИ во Франции",
            params = mapOf("query" to "Проведи исследование про ИИ во Франции", "mode" to SearchMode.RESEARCH.name, "maxSources" to 6)
        ),
        FewShotExample(
            request = "Нужно найти подходящую библиотеку для создания презентаций",
            params = mapOf("query" to "Нужно найти подходящую библиотеку для создания презентаций", "mode" to SearchMode.RESEARCH.name, "maxSources" to 5)
        ),
    )

    override val returnParameters: ReturnParameters = ReturnParameters(
        properties = mapOf(
            "status" to ReturnProperty("string", "Result status: COMPLETE, PARTIAL, or NO_RESULTS"),
            "mode" to ReturnProperty("string", "Resolved mode: QUICK_ANSWER or RESEARCH"),
            "query" to ReturnProperty("string", "Original user query"),
            "answer" to ReturnProperty("string", "Synthesized answer"),
            "reportMarkdown" to ReturnProperty("string", "Ready-to-send markdown report or an inline preview when the full report was exported to a file"),
            "reportFilePath" to ReturnProperty("string", "Absolute path to a saved .md research report when status=COMPLETE and the report was too large to keep inline"),
            "sources" to ReturnProperty("array", "Sources actually used in the final answer"),
            "strategy" to ReturnProperty("object", "Search strategy used for research mode"),
        )
    )

    override fun invoke(input: Input): String = runBlocking { suspendInvoke(input) }

    override suspend fun suspendInvoke(input: Input): String {
        val query = input.query.trim()
        if (query.isBlank()) throw BadInputException("`query` is required")

        val resolvedMode = input.mode ?: inferMode(query)
        val output = when (resolvedMode) {
            SearchMode.QUICK_ANSWER -> runQuickAnswer(query, input.maxSources)
            SearchMode.RESEARCH -> runResearch(query, input.maxSources)
        }
        return mapper.writeValueAsString(output)
    }

    private suspend fun runQuickAnswer(query: String, maxSources: Int): Output {
        val sources = collectSources(
            searchQueries = listOf(query),
            maxSources = maxSources.coerceIn(1, 3),
            resultsPerQuery = 5,
            pageCharLimit = QUICK_PAGE_TEXT_LIMIT,
        )
        if (sources.isEmpty()) {
            return buildNoResultsOutput(query = query, mode = SearchMode.QUICK_ANSWER, strategy = null)
        }

        val synthesis = synthesizeAnswer(
            query = query,
            mode = SearchMode.QUICK_ANSWER,
            sources = sources,
            strategy = null,
        )
        val usedSources = selectUsedSources(sources, synthesis.draft.usedSourceIndexes)

        return buildOutput(
            query = query,
            mode = SearchMode.QUICK_ANSWER,
            status = synthesis.status,
            answer = synthesis.draft.answer.orEmpty(),
            reportBody = synthesis.draft.reportMarkdown ?: synthesis.draft.answer.orEmpty(),
            sources = usedSources,
            strategy = null,
        )
    }

    private suspend fun runResearch(query: String, maxSources: Int): Output {
        val strategy = buildResearchStrategy(query)
        val sourceBudget = maxOf(DEFAULT_RESEARCH_SOURCE_COUNT, maxSources).coerceIn(DEFAULT_RESEARCH_SOURCE_COUNT, MAX_RESEARCH_SOURCES)
        val sources = collectSources(
            searchQueries = strategy.searchQueries,
            maxSources = sourceBudget,
            resultsPerQuery = RESEARCH_RESULTS_PER_QUERY,
            pageCharLimit = RESEARCH_PAGE_TEXT_LIMIT,
        )
        if (sources.isEmpty()) {
            return buildNoResultsOutput(query = query, mode = SearchMode.RESEARCH, strategy = strategy)
        }

        val synthesis = synthesizeAnswer(
            query = query,
            mode = SearchMode.RESEARCH,
            sources = sources,
            strategy = strategy,
        )
        val usedSources = selectUsedSources(
            sources = sources,
            usedIndexes = synthesis.draft.usedSourceIndexes,
            minimumCount = MIN_RESEARCH_SOURCES_IN_REPORT,
        )

        return buildOutput(
            query = query,
            mode = SearchMode.RESEARCH,
            status = synthesis.status,
            answer = synthesis.draft.answer.orEmpty(),
            reportBody = synthesis.draft.reportMarkdown ?: synthesis.draft.answer.orEmpty(),
            sources = usedSources,
            strategy = strategy,
        )
    }

    private suspend fun buildResearchStrategy(query: String): ResearchStrategy {
        val fallback = fallbackStrategy(query)
        val responseText = callLlm(
            systemPrompt = STRATEGY_SYSTEM_PROMPT,
            userPrompt = buildString {
                appendLine("User research request:")
                appendLine(query)
            },
            temperature = 0.2f,
            maxTokens = 900,
        ) ?: return fallback

        val draft = readJsonOrNull<StrategyDraft>(responseText) ?: return fallback
        val searchQueries = sanitizeStrings(
            values = draft.searchQueries,
            fallback = fallback.searchQueries,
            minItems = 4,
            maxItems = MAX_SEARCH_QUERIES,
        )

        return ResearchStrategy(
            goal = draft.goal?.trim().orEmpty().ifBlank { fallback.goal },
            searchQueries = searchQueries,
            subQuestions = sanitizeStrings(draft.subQuestions, emptyList(), 0, 5),
            answerSections = sanitizeStrings(draft.answerSections, emptyList(), 0, 5),
        )
    }

    private fun fallbackStrategy(query: String): ResearchStrategy {
        val englishSuffixes = listOf("overview", "comparison", "official sources")
        val russianSuffixes = listOf("обзор", "сравнение", "официальные источники")
        val suffixes = if (looksRussian(query)) russianSuffixes else englishSuffixes

        return ResearchStrategy(
            goal = query,
            searchQueries = buildList {
                add(query)
                addAll(suffixes.map { "$query $it" })
            }.distinct().take(MAX_SEARCH_QUERIES),
            subQuestions = emptyList(),
            answerSections = emptyList(),
        )
    }

    private fun collectSources(
        searchQueries: List<String>,
        maxSources: Int,
        resultsPerQuery: Int,
        pageCharLimit: Int,
    ): List<CollectedSource> {
        val aggregated = LinkedHashMap<String, CollectedSource>()

        for (searchQuery in searchQueries.take(MAX_SEARCH_QUERIES)) {
            val results = runCatching {
                webResearchClient.searchWeb(searchQuery, resultsPerQuery)
            }.onFailure { error ->
                l.warn("InternetSearch search failed for query '{}': {}", searchQuery, error.message)
            }.getOrDefault(emptyList())

            for (result in results) {
                val normalizedUrl = result.url.trim()
                if (normalizedUrl.isBlank() || aggregated.containsKey(normalizedUrl)) continue

                val pageText = runCatching {
                    webResearchClient.extractPageText(normalizedUrl, pageCharLimit)
                }.onFailure { error ->
                    l.debug("InternetSearch page extraction failed for '{}': {}", normalizedUrl, error.message)
                }.getOrNull()

                aggregated[normalizedUrl] = CollectedSource(
                    index = aggregated.size + 1,
                    title = result.title.trim(),
                    url = normalizedUrl,
                    snippet = result.snippet.trim(),
                    foundByQuery = searchQuery,
                    pageText = pageText?.trim()?.takeIf { it.isNotBlank() },
                )

                if (aggregated.size >= maxSources) break
            }

            if (aggregated.size >= maxSources) break
        }

        return aggregated.values.toList()
    }

    private suspend fun synthesizeAnswer(
        query: String,
        mode: SearchMode,
        sources: List<CollectedSource>,
        strategy: ResearchStrategy?,
    ): SynthesisResult {
        val responseText = callLlm(
            systemPrompt = when (mode) {
                SearchMode.QUICK_ANSWER -> QUICK_ANSWER_SYSTEM_PROMPT
                SearchMode.RESEARCH -> RESEARCH_SYSTEM_PROMPT
            },
            userPrompt = buildSynthesisPrompt(query, mode, sources, strategy),
            temperature = 0.15f,
            maxTokens = when (mode) {
                SearchMode.QUICK_ANSWER -> 900
                SearchMode.RESEARCH -> 3_200
            },
        )
        responseText
            ?.let { recoverSynthesisDraft(raw = it, mode = mode, sources = sources) }
            ?.let { draft ->
                return SynthesisResult(
                    status = OutputStatus.COMPLETE,
                    draft = finalizeSynthesisDraft(draft, sources),
                )
            }

        responseText
            ?.takeIf { shouldAttemptRepairDraft(it, mode) }
            ?.let {
                repairMalformedSynthesis(
                    query = query,
                    mode = mode,
                    rawDraft = it,
                    sources = sources,
                )
            }
            ?.let { draft ->
                return SynthesisResult(
                    status = OutputStatus.COMPLETE,
                    draft = finalizeSynthesisDraft(draft, sources),
                )
            }

        val rescueResponse = callLlm(
            systemPrompt = when (mode) {
                SearchMode.QUICK_ANSWER -> QUICK_ANSWER_RESCUE_SYSTEM_PROMPT
                SearchMode.RESEARCH -> RESEARCH_RESCUE_SYSTEM_PROMPT
            },
            userPrompt = buildRescueSynthesisPrompt(
                query = query,
                mode = mode,
                sources = sources,
                strategy = strategy,
                failedDraft = responseText,
            ),
            temperature = 0.1f,
            maxTokens = when (mode) {
                SearchMode.QUICK_ANSWER -> 1_100
                SearchMode.RESEARCH -> 3_600
            },
        )

        rescueResponse
            ?.let { recoverSynthesisDraft(raw = it, mode = mode, sources = sources) }
            ?.let { draft ->
                return SynthesisResult(
                    status = OutputStatus.COMPLETE,
                    draft = finalizeSynthesisDraft(draft, sources),
                )
            }

        return SynthesisResult(
            status = OutputStatus.PARTIAL,
            draft = fallbackSynthesis(mode, sources),
        )
    }

    private fun recoverSynthesisDraft(
        raw: String,
        mode: SearchMode,
        sources: List<CollectedSource>,
    ): SynthesisDraft? {
        val jsonDraft = readJsonOrNull<SynthesisDraft>(raw)?.normalize(mode)
        if (jsonDraft != null && !jsonDraft.answer.isNullOrBlank()) {
            return jsonDraft
        }
        return normalizeFreeformDraft(raw = raw, mode = mode, sources = sources)
    }

    private fun normalizeFreeformDraft(
        raw: String,
        mode: SearchMode,
        sources: List<CollectedSource>,
    ): SynthesisDraft? {
        val cleaned = stripCodeFences(raw).trim()
        if (cleaned.isBlank()) return null

        return when (mode) {
            SearchMode.QUICK_ANSWER -> SynthesisDraft(
                answer = cleaned,
                reportMarkdown = cleaned,
                usedSourceIndexes = parseInlineSourceIndexes(cleaned, sources),
            )

            SearchMode.RESEARCH -> {
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
                SynthesisDraft(
                    answer = extractExecutiveSummary(cleaned),
                    reportMarkdown = cleaned,
                    usedSourceIndexes = parseInlineSourceIndexes(cleaned, sources),
                )
            }
        }
    }

    private suspend fun repairMalformedSynthesis(
        query: String,
        mode: SearchMode,
        rawDraft: String,
        sources: List<CollectedSource>,
    ): SynthesisDraft? {
        val repairedResponse = callLlm(
            systemPrompt = when (mode) {
                SearchMode.QUICK_ANSWER -> QUICK_ANSWER_REPAIR_SYSTEM_PROMPT
                SearchMode.RESEARCH -> RESEARCH_REPAIR_SYSTEM_PROMPT
            },
            userPrompt = buildRepairPrompt(
                query = query,
                mode = mode,
                rawDraft = rawDraft,
                sources = sources,
            ),
            temperature = 0.0f,
            maxTokens = when (mode) {
                SearchMode.QUICK_ANSWER -> 700
                SearchMode.RESEARCH -> 2_400
            },
        ) ?: return null

        return recoverSynthesisDraft(
            raw = repairedResponse,
            mode = mode,
            sources = sources,
        )
    }

    private fun shouldAttemptRepairDraft(raw: String, mode: SearchMode): Boolean {
        val cleaned = stripCodeFences(raw).trim()
        if (cleaned.isBlank()) return false
        if (cleaned.startsWith("{") || cleaned.startsWith("[")) return true
        return when (mode) {
            SearchMode.QUICK_ANSWER -> cleaned.length >= 60
            SearchMode.RESEARCH -> cleaned.length >= 240
        }
    }

    private fun finalizeSynthesisDraft(
        draft: SynthesisDraft,
        sources: List<CollectedSource>,
    ): SynthesisDraft {
        return draft.copy(
            answer = draft.answer?.trim(),
            reportMarkdown = draft.reportMarkdown?.trim(),
            usedSourceIndexes = sanitizeIndexes(draft.usedSourceIndexes, sources),
        )
    }

    private fun buildSynthesisPrompt(
        query: String,
        mode: SearchMode,
        sources: List<CollectedSource>,
        strategy: ResearchStrategy?,
    ): String = buildString {
        appendLine("User query:")
        appendLine(query)
        appendLine()

        if (mode == SearchMode.RESEARCH && strategy != null) {
            appendLine("Research strategy:")
            appendLine("Goal: ${strategy.goal}")
            if (strategy.subQuestions.isNotEmpty()) {
                appendLine("Sub-questions:")
                strategy.subQuestions.forEach { appendLine("- $it") }
            }
            if (strategy.answerSections.isNotEmpty()) {
                appendLine("Preferred answer sections:")
                strategy.answerSections.forEach { appendLine("- $it") }
            }
            appendLine()
        }

        appendLine("Sources:")
        sources.forEach { source ->
            appendLine("[${source.index}] ${source.title}")
            appendLine("URL: ${source.url}")
            appendLine("Found by query: ${source.foundByQuery}")
            if (source.snippet.isNotBlank()) {
                appendLine("Snippet: ${source.snippet}")
            }
            source.pageText?.takeIf { it.isNotBlank() }?.let { pageText ->
                appendLine("Extracted text:")
                appendLine(pageText.take(MAX_SOURCE_TEXT_FOR_PROMPT))
            }
            appendLine()
        }
    }.trim()

    private fun buildRescueSynthesisPrompt(
        query: String,
        mode: SearchMode,
        sources: List<CollectedSource>,
        strategy: ResearchStrategy?,
        failedDraft: String?,
    ): String = buildString {
        appendLine("User query:")
        appendLine(query)
        appendLine()

        if (mode == SearchMode.RESEARCH && strategy != null) {
            appendLine("Original research strategy:")
            appendLine("Goal: ${strategy.goal}")
            strategy.searchQueries.forEach { appendLine("- $it") }
            appendLine()
        }

        failedDraft
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { raw ->
                appendLine("Previous synthesis attempt was malformed or incomplete. Reuse any valid material from it, but do not invent missing facts:")
                appendLine(raw.take(MAX_FAILED_DRAFT_CHARS))
                appendLine()
            }

        appendLine("Compact source digest:")
        sources.forEach { source ->
            appendLine("[${source.index}] ${source.title}")
            appendLine("URL: ${source.url}")
            appendLine("Found by query: ${source.foundByQuery}")
            if (source.snippet.isNotBlank()) {
                appendLine("Snippet: ${source.snippet}")
            }
            source.pageText?.takeIf { it.isNotBlank() }?.let { pageText ->
                appendLine("Evidence:")
                appendLine(pageText.take(MAX_SOURCE_TEXT_FOR_RESCUE_PROMPT))
            }
            appendLine()
        }
    }.trim()

    private fun buildRepairPrompt(
        query: String,
        mode: SearchMode,
        rawDraft: String,
        sources: List<CollectedSource>,
    ): String = buildString {
        appendLine("User query:")
        appendLine(query)
        appendLine()
        appendLine("Mode: ${mode.name}")
        appendLine("Allowed source indexes: ${sources.joinToString(", ") { it.index.toString() }}")
        appendLine()
        appendLine("Malformed draft to repair into valid JSON:")
        appendLine(rawDraft.take(MAX_FAILED_DRAFT_CHARS))
    }.trim()

    private fun fallbackSynthesis(
        mode: SearchMode,
        sources: List<CollectedSource>,
    ): SynthesisDraft {
        val preview = sources.joinToString(separator = "\n") { source ->
            val detail = source.snippet.ifBlank { source.pageText?.take(220).orEmpty() }.ifBlank { "No preview available." }
            "[${source.index}] ${source.title}: $detail"
        }

        val intro = when (mode) {
            SearchMode.QUICK_ANSWER -> "Не удалось надёжно синтезировать короткий ответ. Ниже ключевые найденные источники."
            SearchMode.RESEARCH -> "Не удалось собрать надёжный финальный ресерч-ответ. Ниже черновой digest по найденным источникам для ручной проверки."
        }

        return SynthesisDraft(
            answer = "$intro\n\n$preview",
            reportMarkdown = "$intro\n\n$preview",
            usedSourceIndexes = sources.map { it.index },
        )
    }

    private fun SynthesisDraft.normalize(mode: SearchMode): SynthesisDraft? {
        val normalizedAnswer = answer?.trim().takeIf { !it.isNullOrBlank() }
        val normalizedReport = reportMarkdown?.trim().takeIf { !it.isNullOrBlank() }
        val resolvedAnswer = normalizedAnswer ?: when (mode) {
            SearchMode.QUICK_ANSWER -> normalizedReport
            SearchMode.RESEARCH -> normalizedReport?.let(::extractExecutiveSummary)
        }
        val resolvedReport = normalizedReport ?: resolvedAnswer
        if (resolvedAnswer.isNullOrBlank() && resolvedReport.isNullOrBlank()) return null
        return copy(answer = resolvedAnswer, reportMarkdown = resolvedReport)
    }

    private fun parseInlineSourceIndexes(
        text: String,
        sources: List<CollectedSource>,
    ): List<Int> {
        val allowed = sources.map { it.index }.toSet()
        return Regex("\\[(\\d+)]")
            .findAll(text)
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .filter { it in allowed }
            .distinct()
            .toList()
    }

    private fun selectUsedSources(
        sources: List<CollectedSource>,
        usedIndexes: List<Int>,
    ): List<CollectedSource> {
        return selectUsedSources(
            sources = sources,
            usedIndexes = usedIndexes,
            minimumCount = DEFAULT_FALLBACK_SOURCE_COUNT,
        )
    }

    private fun selectUsedSources(
        sources: List<CollectedSource>,
        usedIndexes: List<Int>,
        minimumCount: Int,
    ): List<CollectedSource> {
        val selected = LinkedHashMap<Int, CollectedSource>()
        sources.filter { it.index in usedIndexes }.forEach { selected[it.index] = it }
        sources.forEach { source ->
            if (selected.size >= minimumCount) return@forEach
            selected.putIfAbsent(source.index, source)
        }
        return selected.values.toList()
    }

    private fun buildOutput(
        query: String,
        mode: SearchMode,
        status: OutputStatus,
        answer: String,
        reportBody: String,
        sources: List<CollectedSource>,
        strategy: ResearchStrategy?,
    ): Output {
        val localizedSourcesHeading = if (looksRussian(query)) "Источники" else "Sources"
        val localizedStrategyHeading = if (looksRussian(query)) "Стратегия поиска" else "Search strategy"
        val fullReportMarkdown = buildReportMarkdown(
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
            status == OutputStatus.COMPLETE &&
            mode == SearchMode.RESEARCH &&
            fullReportMarkdown.length >= MAX_INLINE_RESEARCH_REPORT_CHARS
        ) {
            saveResearchReport(query = query, reportMarkdown = fullReportMarkdown)
        } else {
            null
        }
        val answerWithFileNote = if (reportFilePath != null) {
            appendReportFileNote(answer.trim(), reportFilePath, query)
        } else {
            answer.trim()
        }
        val reportMarkdown = if (reportFilePath != null) {
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

        return Output(
            status = status.name,
            mode = mode.name,
            query = query,
            answer = answerWithFileNote,
            reportMarkdown = reportMarkdown,
            reportFilePath = reportFilePath,
            sources = sources.map { source ->
                OutputSource(
                    index = source.index,
                    title = source.title,
                    url = source.url,
                    foundByQuery = source.foundByQuery,
                    snippet = source.snippet,
                )
            },
            strategy = strategy?.let {
                OutputStrategy(
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
        mode: SearchMode,
        answer: String,
        reportBody: String,
        sources: List<CollectedSource>,
        strategy: ResearchStrategy?,
        localizedStrategyHeading: String,
        localizedSourcesHeading: String,
    ): String = buildString {
        if (mode == SearchMode.RESEARCH) {
            appendLine("# ${query.trim()}")
            appendLine()
            appendLine("## ${if (looksRussian(query)) "Краткий вывод" else "Executive summary"}")
            appendLine(answer)
            if (reportBody.isNotBlank() && reportBody != answer) {
                appendLine()
                appendLine("## ${if (looksRussian(query)) "Подробный отчёт" else "Detailed report"}")
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
        sources: List<CollectedSource>,
        strategy: ResearchStrategy?,
        localizedStrategyHeading: String,
        localizedSourcesHeading: String,
    ): String = buildString {
        append(answer.trim())
        appendLine()
        appendLine()
        appendLine(
            if (looksRussian(query)) {
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

    private fun appendReportFileNote(answer: String, reportFilePath: String, query: String): String {
        val note = if (looksRussian(query)) {
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

    private fun saveResearchReport(query: String, reportMarkdown: String): String? {
        return runCatching {
            val outputDir = filesToolUtil.souzDocumentsDirectoryPath.resolve("internet_research").toFile()
            filesToolUtil.requirePathIsSave(outputDir)
            outputDir.mkdirs()
            val file = File(outputDir, buildResearchFileName(query))
            filesToolUtil.requirePathIsSave(file)
            file.writeText(reportMarkdown, StandardCharsets.UTF_8)
            file.absolutePath
        }.onFailure { error ->
            l.warn("Failed to save research markdown report: {}", error.message)
        }.getOrNull()
    }

    private fun buildResearchFileName(query: String): String {
        val slug = query.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(48)
            .ifBlank { "internet_research" }
        return "${slug}_${System.currentTimeMillis()}.md"
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

    private fun buildNoResultsOutput(
        query: String,
        mode: SearchMode,
        strategy: ResearchStrategy?,
    ): Output {
        val message = if (looksRussian(query)) {
            "Не удалось найти релевантные интернет-источники по этому запросу."
        } else {
            "No relevant internet sources were found for this request."
        }

        return buildOutput(
            query = query,
            mode = mode,
            status = OutputStatus.NO_RESULTS,
            answer = message,
            reportBody = message,
            sources = emptyList(),
            strategy = strategy,
        )
    }

    private suspend fun callLlm(
        systemPrompt: String,
        userPrompt: String,
        temperature: Float,
        maxTokens: Int,
    ): String? {
        val response = runCatching {
            api.message(
                GigaRequest.Chat(
                    model = settingsProvider.gigaModel.alias,
                    messages = listOf(
                        GigaRequest.Message(role = GigaMessageRole.system, content = systemPrompt),
                        GigaRequest.Message(role = GigaMessageRole.user, content = userPrompt),
                    ),
                    functions = emptyList(),
                    temperature = temperature,
                    maxTokens = maxTokens,
                )
            )
        }.onFailure { error ->
            l.warn("InternetSearch LLM call failed: {}", error.message)
        }.getOrNull() ?: return null

        return when (response) {
            is GigaResponse.Chat.Error -> {
                l.warn("InternetSearch LLM error {}: {}", response.status, response.message)
                null
            }

            is GigaResponse.Chat.Ok -> response.choices
                .asReversed()
                .firstOrNull { it.message.content.isNotBlank() }
                ?.message
                ?.content
                ?.trim()
        }
    }

    private inline fun <reified T> readJsonOrNull(raw: String): T? {
        val stripped = stripCodeFences(raw)
        return runCatching { tolerantMapper.readValue<T>(stripped) }.getOrNull()
            ?: runCatching {
                val start = stripped.indexOf('{')
                val end = stripped.lastIndexOf('}')
                if (start >= 0 && end > start) {
                    tolerantMapper.readValue<T>(stripped.substring(start, end + 1))
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

    private fun sanitizeStrings(
        values: List<String>,
        fallback: List<String>,
        minItems: Int,
        maxItems: Int,
    ): List<String> {
        val cleaned = values.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(maxItems)
        return if (cleaned.size >= minItems) cleaned else fallback.take(maxItems)
    }

    private fun sanitizeIndexes(
        indexes: List<Int>,
        sources: List<CollectedSource>,
    ): List<Int> {
        val allowed = sources.map { it.index }.toSet()
        val cleaned = indexes.filter { it in allowed }.distinct()
        return cleaned.ifEmpty { sources.take(DEFAULT_FALLBACK_SOURCE_COUNT).map { it.index } }
    }

    private fun inferMode(query: String): SearchMode {
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
        if (researchSignals.any { normalized.contains(it) }) return SearchMode.RESEARCH

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
            return SearchMode.QUICK_ANSWER
        }

        return if (query.length <= 80 && '?' in query) {
            SearchMode.QUICK_ANSWER
        } else {
            SearchMode.RESEARCH
        }
    }

    private fun looksRussian(text: String): Boolean = text.any { it in '\u0400'..'\u04FF' }

    companion object {
        private const val MAX_SEARCH_QUERIES = 6
        private const val MAX_RESEARCH_SOURCES = 16
        private const val DEFAULT_RESEARCH_SOURCE_COUNT = 10
        private const val MIN_RESEARCH_SOURCES_IN_REPORT = 8
        private const val DEFAULT_FALLBACK_SOURCE_COUNT = 3
        private const val RESEARCH_RESULTS_PER_QUERY = 8
        private const val QUICK_PAGE_TEXT_LIMIT = 3_500
        private const val RESEARCH_PAGE_TEXT_LIMIT = 10_500
        private const val MAX_SOURCE_TEXT_FOR_PROMPT = 4_000
        private const val MAX_SOURCE_TEXT_FOR_RESCUE_PROMPT = 1_600
        private const val MAX_FAILED_DRAFT_CHARS = 4_000
        private const val MAX_INLINE_RESEARCH_REPORT_CHARS = 8_000
        private const val MAX_EXECUTIVE_SUMMARY_CHARS = 1_600
        private const val MIN_RESEARCH_FREEFORM_CHARS = 280

        private const val STRATEGY_SYSTEM_PROMPT = """
You are a web research planner.
Return JSON only with the following shape:
{
  "goal": "string",
  "searchQueries": ["string"],
  "subQuestions": ["string"],
  "answerSections": ["string"]
}
Rules:
- Build 4 to 6 concrete search queries for a real research pass.
- Queries should be diverse enough to cover the topic from different angles.
- Mix source types when relevant: official docs, primary sources, analytical articles, and comparison/review content.
- Prefer official, primary, technical, or analytical sources when relevant.
- Keep JSON valid and do not wrap it in prose.
"""

        private const val QUICK_ANSWER_SYSTEM_PROMPT = """
You are a web answer synthesizer.
Return JSON only with:
{
  "answer": "short markdown answer",
  "usedSourceIndexes": [1, 2]
}
Rules:
- Answer in the same language as the user's query.
- Use only the provided sources.
- Keep the answer concise and direct.
- If the sources are insufficient or conflicting, say so explicitly.
- Use inline source references like [1] or [2] in the answer.
"""

        private const val QUICK_ANSWER_REPAIR_SYSTEM_PROMPT = """
You repair malformed quick-answer drafts.
Return valid JSON only with:
{
  "answer": "short markdown answer",
  "usedSourceIndexes": [1, 2]
}
Rules:
- Use only the information already present in the malformed draft.
- Preserve any existing source references like [1].
- Do not invent new facts.
"""

        private const val QUICK_ANSWER_RESCUE_SYSTEM_PROMPT = """
You are a fallback web answer synthesizer.
Return valid JSON only with:
{
  "answer": "short markdown answer",
  "usedSourceIndexes": [1, 2]
}
Rules:
- Answer in the same language as the user's query.
- Use only the provided source digest.
- Keep the answer concise and direct.
- If evidence is incomplete, say so explicitly.
- Use inline source references like [1] or [2].
"""

        private const val RESEARCH_SYSTEM_PROMPT = """
You are a web research synthesizer.
Return JSON only with:
{
  "answer": "executive summary in markdown",
  "reportMarkdown": "full detailed markdown report",
  "usedSourceIndexes": [1, 2, 3]
}
Rules:
- Answer in the same language as the user's query.
- Base the answer only on the provided sources.
- `answer` must be a compact executive summary: usually 2 to 4 paragraphs with the direct conclusion and the main caveats.
- `reportMarkdown` must be the full research report, not a short summary.
- Start the report with the direct conclusion, then support it with the most relevant findings.
- Prefer 6 to 10 markdown sections when source coverage allows.
- Usually aim for roughly 1,500 to 3,000 words unless the topic is genuinely narrow or the sources are sparse.
- Cover evidence, comparison, tradeoffs, notable examples, limitations, and open questions where relevant.
- When the user asks to choose a library/tool/approach, provide a reasoned recommendation and the main tradeoffs.
- Mention uncertainty or missing evidence when necessary.
- Cite multiple sources throughout the report and try to use at least 6 distinct sources when available.
- Use inline source references like [1], [2], [3].
- Keep the JSON valid and do not wrap it in prose.
"""

        private const val RESEARCH_REPAIR_SYSTEM_PROMPT = """
You repair malformed research drafts.
Return valid JSON only with:
{
  "answer": "executive summary in markdown",
  "reportMarkdown": "full detailed markdown report",
  "usedSourceIndexes": [1, 2, 3]
}
Rules:
- Use only the information already present in the malformed draft.
- Preserve existing source references like [1].
- Do not invent facts or expand beyond the draft.
- If the draft is incomplete, keep the answer conservative but still valid JSON.
"""

        private const val RESEARCH_RESCUE_SYSTEM_PROMPT = """
You are a fallback web research synthesizer.
Return valid JSON only with:
{
  "answer": "executive summary in markdown",
  "reportMarkdown": "full detailed markdown report",
  "usedSourceIndexes": [1, 2, 3]
}
Rules:
- Answer in the same language as the user's query.
- Use only the provided source digest and any clearly valid material from the previous draft.
- Do not invent missing facts.
- `answer` must be a compact executive summary with the main conclusion and caveats.
- `reportMarkdown` must still be a useful long-form report: prefer structured sections and explicit uncertainty over failure.
- If evidence is incomplete, say what is well-supported and what remains uncertain.
- Use inline source references like [1], [2], [3].
- Keep the JSON valid and do not wrap it in prose.
"""
    }
}
