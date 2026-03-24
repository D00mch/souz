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
 * Plain text extraction from a single web page.
 *
 * Tool wrapper over [WebResearchClient.extractPageText].
 * Output always contains only extracted page text.
 */
class ToolWebPageText(
    private val webResearchClient: WebResearchClient,
    private val mapper: ObjectMapper = gigaJsonMapper,
) : ToolSetup<ToolWebPageText.Input> {
    data class WebPageTextOutput(
        val url: String,
        val pageText: String,
    )

    data class Input(
        @InputParamDescription("Page URL (must start with http:// or https://)")
        val url: String,
        @InputParamDescription("Maximum number of extracted text characters")
        val maxChars: Int = 6000,
    )

    override val name: String = "WebPageText"
    override val description: String = "Fetches one web page and extracts normalized plain text."

    override val fewShotExamples: List<FewShotExample> = listOf(
        FewShotExample(
            request = "Извлеки текст со страницы отчета",
            params = mapOf("url" to "https://example.com/report")
        ),
        FewShotExample(
            request = "Прочитай страницу и дай сырой текст",
            params = mapOf("url" to "https://openai.com", "maxChars" to 5000)
        ),
    )

    override val returnParameters: ReturnParameters = ReturnParameters(
        properties = mapOf(
            "url" to ReturnProperty("string", "Original URL"),
            "pageText" to ReturnProperty("string", "Extracted page text"),
        )
    )

    override fun describeAction(input: Input): ToolActionDescriptor? = ToolActionDescriptor(
        kind = ToolActionKind.READ_WEB_PAGE,
        primary = ToolActionValueFormatter.host(input.url),
    )

    override fun invoke(input: Input): String {
        val url = input.url.trim()
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            throw BadInputException("`url` must start with http:// or https://")
        }
        val output = WebPageTextOutput(
            url = url,
            pageText = webResearchClient.extractPageText(url = url, maxChars = input.maxChars.coerceIn(500, 20_000))
        )
        return mapper.writeValueAsString(output)
    }
}
