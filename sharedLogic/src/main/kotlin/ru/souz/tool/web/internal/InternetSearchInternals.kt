package ru.souz.tool.web.internal

import com.fasterxml.jackson.databind.ObjectMapper

class InternetSearchInternals(
    mapper: ObjectMapper,
) {
    private val webToolSupport = WebToolSupport()
    private val support = InternetSearchSupport()
    private val prompts = InternetSearchPrompts(support)
    private val formatter = InternetSearchReportFormatter(support)
    private val draftParser = InternetSearchDraftParser(mapper)

    val maxSearchQueries: Int = support.maxSearchQueries
    val maxResearchSources: Int = support.maxResearchSources
    val researchResultsPerQuery: Int = support.researchResultsPerQuery
    val quickPageTextLimit: Int = support.quickPageTextLimit
    val researchPageTextLimit: Int = support.researchPageTextLimit
    val strategySystemPrompt: String = prompts.strategySystemPrompt

    fun requireWebQuery(raw: String): String = webToolSupport.requireWebQuery(raw)

    fun fallbackResearchStrategy(query: String): InternetSearchResearchStrategy =
        support.fallbackResearchStrategy(query)

    fun sanitizeSearchStrings(
        values: List<String>,
        fallback: List<String>,
        minItems: Int,
        maxItems: Int,
    ): List<String> = support.sanitizeSearchStrings(values, fallback, minItems, maxItems)

    fun selectUsedSources(
        sources: List<InternetSearchCollectedSource>,
        usedIndexes: List<Int>,
    ): List<InternetSearchCollectedSource> = support.selectUsedSources(sources, usedIndexes)

    fun buildFallbackDraft(
        kind: InternetSearchKind,
        query: String,
        sources: List<InternetSearchCollectedSource>,
    ): InternetSearchSynthesisDraft = support.buildFallbackDraft(kind, query, sources)

    fun buildEmptySourcesMessage(
        query: String,
        status: InternetSearchOutputStatus,
    ): String = support.buildEmptySourcesMessage(query, status)

    fun readStrategyDraft(raw: String): InternetSearchStrategyDraft? = draftParser.readStrategyDraft(raw)

    fun recoverSynthesisDraft(
        raw: String,
        kind: InternetSearchKind,
        sources: List<InternetSearchCollectedSource>,
    ): InternetSearchSynthesisDraft? = draftParser.recoverSynthesisDraft(raw, kind, sources)

    fun isGrounded(draft: InternetSearchSynthesisDraft): Boolean = draftParser.isGrounded(draft)

    fun buildStrategyPrompt(query: String): String = prompts.buildStrategyPrompt(query)

    fun buildSynthesisPrompt(
        query: String,
        kind: InternetSearchKind,
        sources: List<InternetSearchCollectedSource>,
        strategy: InternetSearchResearchStrategy?,
    ): String = prompts.buildSynthesisPrompt(query, kind, sources, strategy)

    fun buildRescuePrompt(
        query: String,
        kind: InternetSearchKind,
        sources: List<InternetSearchCollectedSource>,
        strategy: InternetSearchResearchStrategy?,
        failedDraft: String?,
    ): String = prompts.buildRescuePrompt(query, kind, sources, strategy, failedDraft)

    fun promptSpec(kind: InternetSearchKind): InternetSearchPromptSpec = prompts.promptSpec(kind)

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
    ): InternetSearchToolOutput = formatter.buildOutput(
        query = query,
        kind = kind,
        status = status,
        answer = answer,
        reportBody = reportBody,
        results = results,
        sources = sources,
        strategy = strategy,
        saveLongReport = saveLongReport,
    )
}
