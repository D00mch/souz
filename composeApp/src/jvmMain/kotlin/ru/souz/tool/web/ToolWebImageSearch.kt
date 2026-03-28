package ru.souz.tool.web

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import ru.souz.giga.gigaJsonMapper
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolSetup
import ru.souz.tool.files.FilesToolUtil
import ru.souz.tool.web.internal.WebImageDownloader
import ru.souz.tool.web.internal.WebResearchClient
import ru.souz.tool.web.internal.WebToolSupport

/**
 * Image discovery on the web.
 *
 * Searches for image candidates by topic and optionally downloads them to local files.
 */
internal class ToolWebImageSearch(
    private val filesToolUtil: FilesToolUtil,
    private val webResearchClient: WebResearchClient = WebResearchClient(),
    private val webImageDownloader: WebImageDownloader = WebImageDownloader(filesToolUtil),
    private val mapper: ObjectMapper = gigaJsonMapper,
    private val webToolSupport: WebToolSupport = WebToolSupport(),
) : ToolSetup<ToolWebImageSearch.Input> {
    data class WebImageSearchOutput(
        val query: String,
        val results: List<ResultItem>,
    )

    data class ResultItem(
        val title: String,
        val imageUrl: String,
        val pageUrl: String?,
        val thumbnailUrl: String?,
        val license: String?,
        val localPath: String?,
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

    override fun invoke(input: Input): String = runBlocking { suspendInvoke(input) }

    override suspend fun suspendInvoke(input: Input): String {
        val query = webToolSupport.requireWebQuery(input.query)
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

        return mapper.writeValueAsString(
            WebImageSearchOutput(
                query = query,
                results = results.map { candidate ->
                    ResultItem(
                        title = candidate.title,
                        imageUrl = candidate.imageUrl,
                        pageUrl = candidate.pageUrl,
                        thumbnailUrl = candidate.thumbnailUrl,
                        license = candidate.license,
                        localPath = candidate.localPath,
                    )
                },
            )
        )
    }
}
