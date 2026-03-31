package ru.souz.tool.web

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.restJsonMapper
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolSetup
import ru.souz.tool.files.FilesToolUtil
import ru.souz.tool.web.internal.InternetSearchExecutor
import ru.souz.tool.web.internal.WebResearchClient

internal class ToolInternetResearch(
    private val api: LLMChatAPI,
    private val settingsProvider: SettingsProvider,
    private val filesToolUtil: FilesToolUtil,
    private val webResearchClient: WebResearchClient = WebResearchClient(),
    private val mapper: ObjectMapper = restJsonMapper,
    private val executor: InternetSearchExecutor = InternetSearchExecutor(
        api = api,
        settingsProvider = settingsProvider,
        filesToolUtil = filesToolUtil,
        webResearchClient = webResearchClient,
        mapper = mapper,
    ),
) : ToolSetup<ToolInternetResearch.Input> {
    data class Input(
        @InputParamDescription("User's deep internet research request")
        val query: String,
        @InputParamDescription("Maximum number of source pages to study (1..16). For serious research usually use 8..16.")
        val maxSources: Int = 10,
    )

    override val name: String = "InternetResearch"
    override val description: String =
        "Deep multi-source internet research. Returns a long-form cited answer plus the collected search results. Use this for comparisons, library/tool selection, thematic обзоры, market analysis, or requests that need planning, broader coverage, and a long-form cited report."

    override val fewShotExamples: List<FewShotExample> = listOf(
        FewShotExample(
            request = "Проведи исследование про ИИ во Франции",
            params = mapOf("query" to "Проведи исследование про ИИ во Франции", "maxSources" to 6)
        ),
        FewShotExample(
            request = "Нужно найти подходящую библиотеку для создания презентаций",
            params = mapOf("query" to "Нужно найти подходящую библиотеку для создания презентаций", "maxSources" to 5)
        ),
    )

    override val returnParameters: ReturnParameters = ReturnParameters(
        properties = mapOf(
            "status" to ReturnProperty("string", "Result status: COMPLETE, PARTIAL, NO_RESULTS, PROVIDER_BLOCKED, or PROVIDER_UNAVAILABLE"),
            "query" to ReturnProperty("string", "Original user query"),
            "answer" to ReturnProperty("string", "Executive summary"),
            "reportMarkdown" to ReturnProperty("string", "Detailed markdown report or inline preview when the full report was exported to a file"),
            "reportFilePath" to ReturnProperty("string", "Absolute path to a saved .md report when the full research was exported"),
            "results" to ReturnProperty("array", "Collected search results/pages studied during the research pass"),
            "sources" to ReturnProperty("array", "Sources actually cited in the final answer"),
            "strategy" to ReturnProperty("object", "Search strategy used for the research pass"),
        )
    )

    override fun invoke(input: Input): String = runBlocking { suspendInvoke(input) }

    override suspend fun suspendInvoke(input: Input): String =
        mapper.writeValueAsString(executor.runResearch(input.query, input.maxSources))
}
