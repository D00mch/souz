package ru.souz.tool.web.internal

import com.fasterxml.jackson.databind.ObjectMapper
import ru.souz.tool.FewShotExample
import ru.souz.tool.ReturnParameters
import ru.souz.tool.web.ToolInternetSearch

internal class InternetSearchInternals(
    mapper: ObjectMapper,
) {
    private val webToolSupport = WebToolSupport()
    private val support = InternetSearchSupport()
    private val metadata = InternetSearchMetadata()
    private val prompts = InternetSearchPrompts(support)
    private val formatter = InternetSearchReportFormatter(support)
    private val draftParser = InternetSearchDraftParser(mapper)

    val description: String = metadata.description
    val fewShotExamples: List<FewShotExample> = metadata.fewShotExamples
    val returnParameters: ReturnParameters = metadata.returnParameters

    val maxSearchQueries: Int = support.maxSearchQueries
    val maxResearchSources: Int = support.maxResearchSources
    val researchResultsPerQuery: Int = support.researchResultsPerQuery
    val quickPageTextLimit: Int = support.quickPageTextLimit
    val researchPageTextLimit: Int = support.researchPageTextLimit
    val strategySystemPrompt: String = prompts.strategySystemPrompt

    fun requireWebQuery(raw: String): String = webToolSupport.requireWebQuery(raw)

    fun inferMode(query: String): ToolInternetSearch.SearchMode = support.inferMode(query)

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
        mode: ToolInternetSearch.SearchMode,
        query: String,
        sources: List<InternetSearchCollectedSource>,
    ): InternetSearchSynthesisDraft = support.buildFallbackDraft(mode, query, sources)

    fun buildEmptySourcesMessage(
        query: String,
        status: ToolInternetSearch.OutputStatus,
    ): String = support.buildEmptySourcesMessage(query, status)

    fun readStrategyDraft(raw: String): InternetSearchStrategyDraft? = draftParser.readStrategyDraft(raw)

    fun recoverSynthesisDraft(
        raw: String,
        mode: ToolInternetSearch.SearchMode,
        sources: List<InternetSearchCollectedSource>,
    ): InternetSearchSynthesisDraft? = draftParser.recoverSynthesisDraft(raw, mode, sources)

    fun isGrounded(draft: InternetSearchSynthesisDraft): Boolean = draftParser.isGrounded(draft)

    fun buildStrategyPrompt(query: String): String = prompts.buildStrategyPrompt(query)

    fun buildSynthesisPrompt(
        query: String,
        mode: ToolInternetSearch.SearchMode,
        sources: List<InternetSearchCollectedSource>,
        strategy: InternetSearchResearchStrategy?,
    ): String = prompts.buildSynthesisPrompt(query, mode, sources, strategy)

    fun buildRescuePrompt(
        query: String,
        mode: ToolInternetSearch.SearchMode,
        sources: List<InternetSearchCollectedSource>,
        strategy: InternetSearchResearchStrategy?,
        failedDraft: String?,
    ): String = prompts.buildRescuePrompt(query, mode, sources, strategy, failedDraft)

    fun promptSpec(mode: ToolInternetSearch.SearchMode): InternetSearchPromptSpec = prompts.promptSpec(mode)

    fun buildOutput(
        query: String,
        mode: ToolInternetSearch.SearchMode,
        status: ToolInternetSearch.OutputStatus,
        answer: String,
        reportBody: String,
        sources: List<InternetSearchCollectedSource>,
        strategy: InternetSearchResearchStrategy?,
        saveLongReport: (String) -> String?,
    ): ToolInternetSearch.Output = formatter.buildOutput(
        query = query,
        mode = mode,
        status = status,
        answer = answer,
        reportBody = reportBody,
        sources = sources,
        strategy = strategy,
        saveLongReport = saveLongReport,
    )
}
