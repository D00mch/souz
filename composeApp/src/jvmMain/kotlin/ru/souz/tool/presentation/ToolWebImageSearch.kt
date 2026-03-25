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
 * Image discovery on the web.
 *
 * Tool wrapper over [WebResearchClient.searchImages] with optional local download.
 * If `downloadImages=true`, local paths are produced via [WebImageDownloader].
 * Output always contains only image results.
 */
class ToolWebImageSearch(
    private val webResearchClient: WebResearchClient,
    private val webImageDownloader: WebImageDownloader,
    private val mapper: ObjectMapper = gigaJsonMapper,
) : ToolSetup<ToolWebImageSearch.Input> {
    data class WebImageSearchOutput(
        val query: String,
        val results: List<WebImageResult>,
    )

    data class Input(
        @InputParamDescription("Search query for image discovery")
        val query: String,
        @InputParamDescription("Maximum number of image results to return (1..20).")
        val limit: Int = 8,
        @InputParamDescription("Download image results locally and return absolute paths")
        val downloadImages: Boolean = false,
        @InputParamDescription("Output directory for downloaded images. Defaults to ~/Documents/souz/web_assets")
        val outputDir: String? = null,
    )

    override val name: String = "WebImageSearch"
    override val description: String =
        "Searches for image candidates by topic and optionally downloads them to local files."

    override val fewShotExamples: List<FewShotExample> = listOf(
        FewShotExample(
            request = "Подбери изображения для презентации про IndiaAI Summit",
            params = mapOf("query" to "IndiaAI Summit 2026 event photos", "limit" to 8)
        ),
        FewShotExample(
            request = "Найди и скачай изображения для слайдов про renewable energy",
            params = mapOf("query" to "renewable energy infrastructure photos", "limit" to 6, "downloadImages" to true)
        ),
    )

    override val returnParameters: ReturnParameters = ReturnParameters(
        properties = mapOf(
            "query" to ReturnProperty("string", "Original query"),
            "results" to ReturnProperty("array", "Image results with remote URLs and optional local paths"),
        )
    )

    override fun describeAction(input: Input): ToolActionDescriptor? = if (input.downloadImages) {
            ToolActionKind.SEARCH_IMAGES_AND_DOWNLOAD
        } else {
            ToolActionKind.SEARCH_IMAGES
        }.textAction(input.query)

    override fun invoke(input: Input): String {
        val query = input.query.trim()
        if (query.isBlank()) throw BadInputException("`query` is required")
        val limit = input.limit.coerceIn(1, 20)

        val results = webResearchClient.searchImages(query = query, limit = limit).map { candidate ->
            if (!input.downloadImages) {
                candidate
            } else {
                val localPath = runCatching {
                    webImageDownloader.downloadToDirectory(
                        imageUrl = candidate.imageUrl,
                        preferredName = candidate.title.ifBlank { "image" },
                        outputDir = input.outputDir,
                    )
                }.getOrNull()
                candidate.copy(localPath = localPath)
            }
        }

        return mapper.writeValueAsString(WebImageSearchOutput(query = query, results = results))
    }
}
