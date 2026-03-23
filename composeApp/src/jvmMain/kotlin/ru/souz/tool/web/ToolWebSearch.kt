package ru.souz.tool.web

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import ru.souz.giga.gigaJsonMapper
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolSetup
import ru.souz.tool.web.internal.WebResearchClient
import ru.souz.tool.web.internal.WebToolSupport

/**
 * Generic web search by query.
 *
 * Low-level web search tool that returns raw search results.
 */
class ToolWebSearch internal constructor(
    private val webResearchClient: WebResearchClient,
    private val mapper: ObjectMapper,
    private val webToolSupport: WebToolSupport,
) : ToolSetup<ToolWebSearch.Input> {
    constructor(
        mapper: ObjectMapper = gigaJsonMapper,
    ) : this(
        webResearchClient = WebResearchClient(mapper = mapper),
        mapper = mapper,
        webToolSupport = WebToolSupport(),
    )

    internal constructor(
        webResearchClient: WebResearchClient,
        mapper: ObjectMapper = gigaJsonMapper,
    ) : this(
        webResearchClient = webResearchClient,
        mapper = mapper,
        webToolSupport = WebToolSupport(),
    )

    data class WebSearchOutput(
        val query: String,
        val results: List<ResultItem>,
    )

    data class ResultItem(
        val title: String,
        val url: String,
        val snippet: String,
    )

    data class Input(
        @InputParamDescription("Search query")
        val query: String,
        @InputParamDescription("Maximum number of results to return (1..20). For presentation work prefer 6-10.")
        val limit: Int = 8,
    )

    override val name: String = "WebSearch"
    override val description: String =
        "Low-level raw web search. Returns title, URL, and snippet only. Prefer `InternetSearch` for end-user answers, simple web questions, or multi-step research."

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

    override fun invoke(input: Input): String = runBlocking { suspendInvoke(input) }

    override suspend fun suspendInvoke(input: Input): String {
        val query = webToolSupport.requireWebQuery(input.query)
        val output = WebSearchOutput(
            query = query,
            results = webResearchClient.searchWeb(query = query, limit = input.limit.coerceIn(1, 20)).map { result ->
                ResultItem(
                    title = result.title,
                    url = result.url,
                    snippet = result.snippet,
                )
            }
        )
        return mapper.writeValueAsString(output)
    }
}
