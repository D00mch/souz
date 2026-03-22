package ru.souz.tool.web

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import ru.souz.giga.gigaJsonMapper
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
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
    override val description: String =
        "Low-level helper that fetches one web page and extracts normalized plain text. Prefer `InternetSearch` for user-facing internet answers and research."

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

    override fun invoke(input: Input): String = runBlocking { suspendInvoke(input) }

    override suspend fun suspendInvoke(input: Input): String {
        val url = requireHttpUrl(input.url)
        val output = WebPageTextOutput(
            url = url,
            pageText = webResearchClient.extractPageText(url = url, maxChars = input.maxChars.coerceIn(500, 20_000))
        )
        return mapper.writeValueAsString(output)
    }
}
