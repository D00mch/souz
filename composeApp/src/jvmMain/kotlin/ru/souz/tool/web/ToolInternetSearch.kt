package ru.souz.tool.web

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import ru.souz.db.SettingsProvider
import ru.souz.llms.giga.GigaChatAPI
import ru.souz.llms.giga.gigaJsonMapper
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolSetup
import ru.souz.tool.files.FilesToolUtil
import ru.souz.tool.web.internal.InternetSearchExecutor
import ru.souz.tool.web.internal.WebResearchClient

internal class ToolInternetSearch(
    private val api: GigaChatAPI,
    private val settingsProvider: SettingsProvider,
    private val filesToolUtil: FilesToolUtil,
    private val webResearchClient: WebResearchClient = WebResearchClient(),
    private val mapper: ObjectMapper = gigaJsonMapper,
    private val executor: InternetSearchExecutor = InternetSearchExecutor(
        api = api,
        settingsProvider = settingsProvider,
        filesToolUtil = filesToolUtil,
        webResearchClient = webResearchClient,
        mapper = mapper,
    ),
) : ToolSetup<ToolInternetSearch.Input> {
    data class Input(
        @InputParamDescription("User's short internet lookup request")
        val query: String,
        @InputParamDescription("Maximum number of source pages to study.")
        val maxSources: Int = 5,
    )

    override val name: String = "InternetSearch"
    override val description: String =
        "Short factual internet lookup. Returns a concise answer plus raw search results and cited sources. Use this for direct questions, current facts, weather, dates, and concise answers from a few sources. Prefer `InternetResearch` for comparisons, tool selection, thematic обзоры, or multi-step analysis."

    override val fewShotExamples: List<FewShotExample> = listOf(
        FewShotExample(
            request = "Какая погода в Таллине",
            params = mapOf("query" to "Какая погода в Таллине", "maxSources" to 3)
        ),
        FewShotExample(
            request = "Когда вышла Kotlin 2.0",
            params = mapOf("query" to "Когда вышла Kotlin 2.0", "maxSources" to 3)
        ),
    )

    override val returnParameters: ReturnParameters = ReturnParameters(
        properties = mapOf(
            "status" to ReturnProperty("string", "Result status: COMPLETE, PARTIAL, NO_RESULTS, PROVIDER_BLOCKED, or PROVIDER_UNAVAILABLE"),
            "query" to ReturnProperty("string", "Original user query"),
            "answer" to ReturnProperty("string", "Synthesized answer"),
            "reportMarkdown" to ReturnProperty("string", "Ready-to-send markdown answer with sources"),
            "reportFilePath" to ReturnProperty("string", "Always null for short internet lookup"),
            "results" to ReturnProperty("array", "Collected search results/pages studied during the lookup"),
            "sources" to ReturnProperty("array", "Sources actually cited in the final answer"),
            "strategy" to ReturnProperty("object", "Always null for short internet lookup"),
        )
    )

    override fun invoke(input: Input): String = runBlocking { suspendInvoke(input) }

    override suspend fun suspendInvoke(input: Input): String =
        mapper.writeValueAsString(executor.runQuickSearch(input.query, input.maxSources))
}
