package ru.souz.tool.presentation

import com.fasterxml.jackson.databind.ObjectMapper
import ru.souz.giga.gigaJsonMapper
import ru.souz.tool.BadInputException
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolSetup

/**
 * Contract for extracting plain text from a single web page.
 *
 * This tool is intentionally mode-free:
 * - `url` is always required
 * - output always contains only extracted page text
 *
 * It replaces the PAGE branch of the removed `WebResearch` multi-mode contract.
 */
data class WebPageTextInput(
    @InputParamDescription("Page URL (must start with http:// or https://)")
    val url: String,
    @InputParamDescription("Maximum number of extracted text characters")
    val maxChars: Int = 6000,
)

data class WebPageTextOutput(
    val url: String,
    val pageText: String,
)

/**
 * Tool wrapper over [WebResearchClient.extractPageText].
 */
class ToolWebPageText(
    private val webResearchClient: WebResearchClient,
    private val mapper: ObjectMapper = gigaJsonMapper,
) : ToolSetup<WebPageTextInput> {
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

    override fun invoke(input: WebPageTextInput): String {
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
