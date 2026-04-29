package ru.souz.tool.web

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import ru.souz.llms.restJsonMapper
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolSetup
import ru.souz.tool.web.internal.WebResearchClient

class ToolWebPageText(
    private val webResearchClient: WebResearchClient = WebResearchClient(),
    private val mapper: ObjectMapper = restJsonMapper,
) : ToolSetup<ToolWebPageText.Input> {
    data class Input(
        @InputParamDescription("Page URL (must start with http:// or https://)")
        val url: String,
        @InputParamDescription("Maximum number of extracted text characters")
        val maxChars: Int = 6000,
    )

    data class Output(
        val url: String,
        val pageText: String,
    )

    override val name: String = "WebPageText"
    override val description: String =
        "Reads one web page by URL and returns extracted plain text."

    override val fewShotExamples: List<FewShotExample> = listOf(
        FewShotExample(
            request = "Извлеки текст со страницы отчета",
            params = mapOf("url" to "https://example.com/report")
        )
    )

    override val returnParameters: ReturnParameters = ReturnParameters(
        properties = mapOf(
            "url" to ReturnProperty("string", "Requested page URL"),
            "pageText" to ReturnProperty("string", "Extracted page text"),
        )
    )

    override fun invoke(input: Input): String = runBlocking { suspendInvoke(input) }

    override suspend fun suspendInvoke(input: Input): String {
        val url = input.url.trim()
        return mapper.writeValueAsString(
            Output(
                url = url,
                pageText = webResearchClient.extractPageText(url, input.maxChars.coerceIn(500, 20_000)),
            )
        )
    }
}
