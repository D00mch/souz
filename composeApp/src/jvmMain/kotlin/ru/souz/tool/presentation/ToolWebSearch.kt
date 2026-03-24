package ru.souz.tool.presentation

import com.fasterxml.jackson.databind.ObjectMapper
import ru.souz.giga.gigaJsonMapper
import ru.souz.tool.BadInputException
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolActionDescriptor
import ru.souz.tool.ToolActionKind
import ru.souz.tool.ToolActionValueFormatter
import ru.souz.tool.ToolSetup

/**
 * Generic web search by query.
 *
 * Tool wrapper over [WebResearchClient.searchWeb].
 * Output always contains only web search results.
 */
class ToolWebSearch(
    private val webResearchClient: WebResearchClient,
    private val mapper: ObjectMapper = gigaJsonMapper,
) : ToolSetup<ToolWebSearch.Input> {
    data class WebSearchOutput(
        val query: String,
        val results: List<WebSearchResult>,
    )

    data class Input(
        @InputParamDescription("Search query")
        val query: String,
        @InputParamDescription("Maximum number of results to return (1..20). For presentation work prefer 6-10.")
        val limit: Int = 8,
    )

    override val name: String = "WebSearch"
    override val description: String =
        "Searches the web for facts and sources. Returns title, URL, and snippet."

    override val fewShotExamples: List<FewShotExample> = listOf(
        FewShotExample(
            request = "Найди свежие материалы про рынок AI-ассистентов",
            params = mapOf("query" to "AI assistant market trends 2026", "limit" to 6)
        ),
        FewShotExample(
            request = "Собери источники по теме кибербезопасности",
            params = mapOf("query" to "enterprise cybersecurity trends", "limit" to 8)
        ),
    )

    override val returnParameters: ReturnParameters = ReturnParameters(
        properties = mapOf(
            "query" to ReturnProperty("string", "Original query"),
            "results" to ReturnProperty("array", "List of web results with title/url/snippet"),
        )
    )

    override fun describeAction(input: Input): ToolActionDescriptor? = ToolActionDescriptor(
        kind = ToolActionKind.SEARCH_WEB,
        primary = ToolActionValueFormatter.compactText(input.query),
    )

    override fun invoke(input: Input): String {
        val query = input.query.trim()
        if (query.isBlank()) throw BadInputException("`query` is required")
        val output = WebSearchOutput(
            query = query,
            results = webResearchClient.searchWeb(query = query, limit = input.limit.coerceIn(1, 20))
        )
        return mapper.writeValueAsString(output)
    }
}
