package ru.souz.tool.presentation

import com.fasterxml.jackson.databind.ObjectMapper
import org.jsoup.Jsoup
import org.kodein.di.DI
import org.kodein.di.instance
import ru.souz.di.mainDiModule
import ru.souz.giga.gigaJsonMapper
import ru.souz.tool.BadInputException
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolSetup
import ru.souz.tool.files.FilesToolUtil
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.Duration
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.min

data class WebResearchInput(
    @InputParamDescription("Research mode: WEB, IMAGE, BOTH, or PAGE (extract text from one URL).")
    val mode: WebResearchMode = WebResearchMode.BOTH,
    @InputParamDescription("Search query for WEB/IMAGE/BOTH modes")
    val query: String? = null,
    @InputParamDescription("Page URL for PAGE mode")
    val url: String? = null,
    @InputParamDescription("Maximum number of results to return (1..20). For presentation work prefer 6-10.")
    val limit: Int = 8,
    @InputParamDescription("Download image results locally and return absolute paths")
    val downloadImages: Boolean = true,
    @InputParamDescription("Output directory for downloaded images. Defaults to ~/souz/Documents/web_assets")
    val outputDir: String? = null,
    @InputParamDescription("Maximum number of extracted text characters for PAGE mode")
    val maxChars: Int = 6000,
)

enum class WebResearchMode {
    WEB,
    IMAGE,
    BOTH,
    PAGE,
}

data class WebSearchResult(
    val title: String,
    val url: String,
    val snippet: String,
)

data class WebImageResult(
    val title: String,
    val imageUrl: String,
    val pageUrl: String?,
    val thumbnailUrl: String?,
    val license: String?,
    val localPath: String?,
)

data class WebResearchOutput(
    val mode: String,
    val query: String?,
    val url: String?,
    val webResults: List<WebSearchResult> = emptyList(),
    val imageResults: List<WebImageResult> = emptyList(),
    val pageText: String? = null,
)

class ToolWebResearch(
    private val filesToolUtil: FilesToolUtil,
    private val mapper: ObjectMapper = gigaJsonMapper,
) : ToolSetup<WebResearchInput> {

    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .connectTimeout(Duration.ofSeconds(6))
        .build()

    override val name: String = "WebResearch"
    override val description: String = "Searches the internet for facts, sources, and images. " +
            "Supports web search, image discovery, page text extraction, and optional local image download. " +
            "For presentation work, use this first to gather references and 6-10 visual candidates before generating slides."

    override val fewShotExamples: List<FewShotExample> = listOf(
        FewShotExample(
            request = "Найди свежие материалы про рынок AI-ассистентов",
            params = mapOf("mode" to "WEB", "query" to "AI assistant market trends 2026", "limit" to 6)
        ),
        FewShotExample(
            request = "Подбери изображения для презентации про IndiaAI Summit",
            params = mapOf("mode" to "IMAGE", "query" to "IndiaAI Summit 2026 event photos", "limit" to 8)
        ),
        FewShotExample(
            request = "Собери факты и картинки по теме кибербезопасности",
            params = mapOf("mode" to "BOTH", "query" to "enterprise cybersecurity trends", "limit" to 8)
        ),
        FewShotExample(
            request = "Извлеки текст со страницы отчета",
            params = mapOf("mode" to "PAGE", "url" to "https://example.com/report")
        ),
    )

    override val returnParameters: ReturnParameters = ReturnParameters(
        properties = mapOf(
            "mode" to ReturnProperty("string", "Resolved research mode"),
            "query" to ReturnProperty("string", "Original query"),
            "url" to ReturnProperty("string", "Original URL for PAGE mode"),
            "webResults" to ReturnProperty("array", "List of web results with title/url/snippet"),
            "imageResults" to ReturnProperty("array", "List of image candidates with remote URLs and optional local paths"),
            "pageText" to ReturnProperty("string", "Extracted plain text from a page in PAGE mode"),
        )
    )

    override fun invoke(input: WebResearchInput): String {
        val limit = input.limit.coerceIn(1, 20)
        val maxChars = input.maxChars.coerceIn(500, 20_000)

        val output = when (input.mode) {
            WebResearchMode.WEB -> {
                val query = input.query?.trim().orEmpty()
                if (query.isBlank()) throw BadInputException("`query` is required for WEB mode")
                WebResearchOutput(
                    mode = input.mode.name,
                    query = query,
                    url = null,
                    webResults = searchWeb(query, limit)
                )
            }

            WebResearchMode.IMAGE -> {
                val query = input.query?.trim().orEmpty()
                if (query.isBlank()) throw BadInputException("`query` is required for IMAGE mode")
                WebResearchOutput(
                    mode = input.mode.name,
                    query = query,
                    url = null,
                    imageResults = searchImages(
                        query = query,
                        limit = limit,
                        downloadImages = input.downloadImages,
                        outputDir = input.outputDir,
                    )
                )
            }

            WebResearchMode.BOTH -> {
                val query = input.query?.trim().orEmpty()
                if (query.isBlank()) throw BadInputException("`query` is required for BOTH mode")
                WebResearchOutput(
                    mode = input.mode.name,
                    query = query,
                    url = null,
                    webResults = searchWeb(query, limit),
                    imageResults = searchImages(
                        query = query,
                        limit = maxOf(limit, 6),
                        downloadImages = input.downloadImages,
                        outputDir = input.outputDir,
                    )
                )
            }

            WebResearchMode.PAGE -> {
                val url = input.url?.trim().orEmpty()
                if (!(url.startsWith("http://") || url.startsWith("https://"))) {
                    throw BadInputException("`url` is required for PAGE mode and must start with http:// or https://")
                }
                WebResearchOutput(
                    mode = input.mode.name,
                    query = null,
                    url = url,
                    pageText = extractPageText(url, maxChars)
                )
            }
        }

        return mapper.writeValueAsString(output)
    }

    private fun searchWeb(query: String, limit: Int): List<WebSearchResult> {
        val targetCount = limit.coerceIn(1, 20)
        val aggregated = LinkedHashMap<String, WebSearchResult>()
        for (variant in buildQueryVariants(query, imageIntent = false).take(4)) {
            runCatching { searchWebSingleQuery(variant, targetCount) }
                .getOrDefault(emptyList())
                .forEach { result ->
                    aggregated.putIfAbsent(result.url, result)
                }
            if (aggregated.size >= targetCount) break
        }
        return aggregated.values.take(targetCount).toList()
    }

    private fun searchWebSingleQuery(query: String, limit: Int): List<WebSearchResult> {
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)
        val html = httpGet("https://duckduckgo.com/html/?q=$encodedQuery", timeoutSeconds = 6)
        val doc = Jsoup.parse(html)

        return doc.select("div.result").asSequence().mapNotNull { result ->
            val link = result.selectFirst("a.result__a") ?: return@mapNotNull null
            val title = link.text().trim()
            val rawHref = link.attr("href").trim()
            val url = decodeDuckDuckGoRedirect(rawHref)
            if (title.isBlank() || url.isBlank()) return@mapNotNull null

            val snippet = result.selectFirst(".result__snippet")?.text()?.trim().orEmpty()
            WebSearchResult(title = title, url = url, snippet = snippet)
        }.distinctBy { it.url }.take(limit).toList()
    }

    private fun searchImages(
        query: String,
        limit: Int,
        downloadImages: Boolean,
        outputDir: String?,
    ): List<WebImageResult> {
        val targetCount = limit.coerceIn(1, 20)
        val aggregated = LinkedHashMap<String, WebImageResult>()
        val fetchLimit = min(16, maxOf(targetCount * 2, 8))
        for (variant in buildQueryVariants(query, imageIntent = true).take(4)) {
            runCatching { searchCommonsImages(variant, fetchLimit) }
                .getOrDefault(emptyList())
                .forEach { candidate ->
                    addImageCandidate(aggregated, candidate)
                }
            if (aggregated.size < targetCount) {
                runCatching { searchPageImageCandidates(variant, fetchLimit) }
                    .getOrDefault(emptyList())
                    .forEach { candidate ->
                        addImageCandidate(aggregated, candidate)
                    }
            }
            if (aggregated.size >= targetCount) break
        }

        return aggregated.values.take(targetCount).map { candidate ->
            if (!downloadImages) {
                candidate
            } else {
                val localPath = runCatching {
                    downloadImage(
                        imageUrl = candidate.imageUrl,
                        preferredName = candidate.title.ifBlank { "image" },
                        outputDir = outputDir,
                    )
                }.getOrNull()
                candidate.copy(localPath = localPath)
            }
        }
    }

    private fun searchCommonsImages(query: String, limit: Int): List<WebImageResult> {
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)
        val url = buildString {
            append("https://commons.wikimedia.org/w/api.php")
            append("?action=query")
            append("&generator=search")
            append("&gsrnamespace=6")
            append("&gsrsearch=")
            append(encodedQuery)
            append("&gsrlimit=")
            append(limit.coerceIn(1, 50))
            append("&prop=imageinfo%7Cpageimages")
            append("&iiprop=url%7Cextmetadata")
            append("&pithumbsize=1200")
            append("&format=json")
            append("&origin=%2A")
        }

        val body = httpGet(url, timeoutSeconds = 8)
        val root = mapper.readTree(body)
        val pages = root.path("query").path("pages")
        if (!pages.isObject) return emptyList()

        return pages.fields().asSequence().mapNotNull { (_, page) ->
            val imageInfoNode = page.path("imageinfo")
            val imageInfo = if (imageInfoNode.isArray && imageInfoNode.size() > 0) imageInfoNode[0] else null
            val imageUrl = imageInfo?.path("url")?.asText().orEmpty()
            if (!isValidImageUrl(imageUrl)) return@mapNotNull null

            WebImageResult(
                title = page.path("title").asText().removePrefix("File:").trim(),
                imageUrl = imageUrl,
                pageUrl = imageInfo?.path("descriptionurl")?.asText(null),
                thumbnailUrl = page.path("thumbnail").path("source").asText(null),
                license = imageInfo?.path("extmetadata")?.path("LicenseShortName")?.path("value")?.asText(null),
                localPath = null,
            )
        }.toList()
    }

    private fun searchPageImageCandidates(query: String, limit: Int): List<WebImageResult> {
        val pageSeeds = LinkedHashMap<String, WebSearchResult>()
        val seedQueries = buildQueryVariants(query, imageIntent = true).take(4)
        for (seedQuery in seedQueries) {
            searchWebSingleQuery(seedQuery, min(MAX_IMAGE_SEED_RESULTS, limit)).forEach { result ->
                if (!isLikelyHtmlPageUrl(result.url)) return@forEach
                pageSeeds.putIfAbsent(result.url, result)
            }
            if (pageSeeds.size >= limit) break
        }

        val results = mutableListOf<WebImageResult>()
        for (page in pageSeeds.values.take(min(limit, MAX_IMAGE_PAGE_FETCHES))) {
            val html = runCatching { httpGet(page.url, timeoutSeconds = 5) }.getOrNull() ?: continue
            val doc = Jsoup.parse(html, page.url)

            val metaUrls = doc.select(
                "meta[property=og:image], meta[property=og:image:url], meta[name=twitter:image], meta[name=twitter:image:src]"
            ).mapNotNull { meta ->
                meta.absUrl("content").takeIf { isValidImageUrl(it) }
            }

            metaUrls.distinct().take(2).forEach { imageUrl ->
                results += WebImageResult(
                    title = page.title,
                    imageUrl = imageUrl,
                    pageUrl = page.url,
                    thumbnailUrl = imageUrl,
                    license = null,
                    localPath = null,
                )
            }

            if (results.size < limit * 2) {
                val inlineUrls = doc.select("img[src]").asSequence().mapNotNull { image ->
                    val src = image.absUrl("src").takeIf { isValidImageUrl(it) } ?: return@mapNotNull null
                    val width = image.attr("width").toIntOrNull() ?: 0
                    val height = image.attr("height").toIntOrNull() ?: 0
                    val score = width * height
                    if (score in 1 until 60_000) return@mapNotNull null
                    src to if (score > 0) score else src.length
                }.sortedByDescending { it.second }.map { it.first }.distinct().take(2).toList()

                inlineUrls.forEach { imageUrl ->
                    results += WebImageResult(
                        title = page.title,
                        imageUrl = imageUrl,
                        pageUrl = page.url,
                        thumbnailUrl = imageUrl,
                        license = null,
                        localPath = null,
                    )
                }
            }

            if (results.size >= limit) break
        }
        return results
    }

    private fun addImageCandidate(
        bucket: LinkedHashMap<String, WebImageResult>,
        candidate: WebImageResult,
    ) {
        val key = candidate.imageUrl.trim().lowercase()
        if (!isValidImageUrl(key)) return
        if (bucket.containsKey(key)) return
        bucket[key] = candidate
    }

    private fun isValidImageUrl(url: String): Boolean {
        if (url.isBlank()) return false
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return false
        if (url.startsWith("data:", ignoreCase = true)) return false
        val extension = extensionFromUrl(url)
        if (extension in blockedDocumentExtensions) return false
        if (extension == "svg") return false
        return true
    }

    private fun isLikelyHtmlPageUrl(url: String): Boolean {
        if (url.isBlank()) return false
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return false
        return extensionFromUrl(url) !in blockedDocumentExtensions
    }

    private fun extractPageText(url: String, maxChars: Int): String {
        val html = httpGet(url)
        val doc = Jsoup.parse(html)
        doc.select("script, style, noscript, svg").remove()
        val normalized = doc.text().replace(Regex("\\s+"), " ").trim()
        return normalized.take(maxChars)
    }

    private fun downloadImage(imageUrl: String, preferredName: String, outputDir: String?): String {
        val dir = resolveImageOutputDir(outputDir)
        val uri = toSafeUri(imageUrl)
        val safeName = preferredName
            .replace(Regex("[^\\p{L}\\p{N}._-]+"), "_")
            .trim('_')
            .ifBlank { "image" }
            .take(80)

        val tempFile = Files.createTempFile("souz_img_", ".bin").toFile()

        try {
            val request = HttpRequest.newBuilder(uri)
                .GET()
                .header("User-Agent", "Mozilla/5.0 (Souz WebResearch)")
                .timeout(Duration.ofSeconds(10))
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofFile(tempFile.toPath()))
            if (response.statusCode() >= 400) {
                throw BadInputException("Image download failed: HTTP ${response.statusCode()} for $imageUrl")
            }
            val contentType = response.headers().firstValue("Content-Type").orElse("")
            val extension = detectDownloadedImageExtension(
                file = tempFile,
                contentType = contentType,
                sourceUrl = imageUrl,
            ) ?: throw BadInputException("Downloaded asset is not an image: $imageUrl")
            val candidate = uniqueFile(File(dir, "$safeName.$extension"))
            Files.move(tempFile.toPath(), candidate.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return candidate.absolutePath
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    private fun uniqueFile(base: File): File {
        if (!base.exists()) return base
        val parent = base.parentFile
        val stem = base.nameWithoutExtension
        val ext = base.extension
        for (idx in 1..500) {
            val candidate = File(parent, "${stem}_$idx.$ext")
            if (!candidate.exists()) return candidate
        }
        return File(parent, "${stem}_${System.currentTimeMillis()}.$ext")
    }

    private fun resolveImageOutputDir(outputDir: String?): File {
        val raw = outputDir?.trim().takeUnless { it.isNullOrBlank() } ?: "~/souz/Documents/web_assets"
        val resolved = File(filesToolUtil.applyDefaultEnvs(raw))
        val dir = if (resolved.isDirectory || raw.endsWith("/") || raw.endsWith("\\")) {
            resolved
        } else {
            resolved.parentFile ?: resolved
        }
        dir.mkdirs()
        filesToolUtil.requirePathIsSave(dir)
        return dir
    }

    private fun decodeDuckDuckGoRedirect(rawHref: String): String {
        if (rawHref.isBlank()) return ""
        if (rawHref.startsWith("http://") || rawHref.startsWith("https://")) return rawHref

        val normalized = when {
            rawHref.startsWith("//") -> "https:$rawHref"
            rawHref.startsWith("/") -> "https://duckduckgo.com$rawHref"
            else -> rawHref
        }

        return runCatching {
            val uri = toSafeUri(normalized)
            val query = uri.rawQuery ?: return@runCatching normalized
            query.split('&').asSequence().mapNotNull { part ->
                val key = part.substringBefore('=', "")
                val value = part.substringAfter('=', "")
                if (key == "uddg") URLDecoder.decode(value, StandardCharsets.UTF_8) else null
            }.firstOrNull().orEmpty().ifBlank { normalized }
        }.getOrDefault(normalized)
    }

    private fun httpGet(url: String, timeoutSeconds: Long = 6): String {
        val request = HttpRequest.newBuilder(toSafeUri(url))
            .GET()
            .header("User-Agent", "Mozilla/5.0 (Souz WebResearch)")
            .header("Accept", "text/html,application/json;q=0.9,*/*;q=0.8")
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (response.statusCode() >= 400) {
            val bodyPreview = response.body().take(min(600, response.body().length))
            throw BadInputException("HTTP ${response.statusCode()} for $url: $bodyPreview")
        }
        return response.body()
    }

    private fun toSafeUri(url: String): URI {
        return URI.create(url.replace(" ", "%20"))
    }

    private fun detectDownloadedImageExtension(
        file: File,
        contentType: String,
        sourceUrl: String,
    ): String? {
        val header = file.inputStream().use { input ->
            ByteArray(64).also { bytes ->
                val read = input.read(bytes)
                if (read < bytes.size && read >= 0) {
                    for (i in read until bytes.size) bytes[i] = 0
                }
            }
        }
        val normalizedContentType = contentType.substringBefore(';').trim().lowercase()
        if (normalizedContentType.contains("pdf")) return null

        sniffImageExtension(header)?.let { return it }

        val byMime = when (normalizedContentType) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/bmp" -> "bmp"
            "image/webp" -> "webp"
            "image/svg+xml" -> null
            "image/avif" -> "avif"
            else -> null
        }
        if (byMime != null) return byMime

        val urlExtension = extensionFromUrl(sourceUrl)
        return urlExtension.takeIf { it in supportedImageExtensions }
    }

    private fun sniffImageExtension(header: ByteArray): String? {
        if (header.size >= 4 &&
            header[0] == 0x25.toByte() &&
            header[1] == 0x50.toByte() &&
            header[2] == 0x44.toByte() &&
            header[3] == 0x46.toByte()
        ) {
            return null
        }
        if (header.size >= 8 &&
            header[0] == 0x89.toByte() &&
            header[1] == 0x50.toByte() &&
            header[2] == 0x4E.toByte() &&
            header[3] == 0x47.toByte()
        ) {
            return "png"
        }
        if (header.size >= 3 &&
            header[0] == 0xFF.toByte() &&
            header[1] == 0xD8.toByte() &&
            header[2] == 0xFF.toByte()
        ) {
            return "jpg"
        }
        val headerText = header.toString(StandardCharsets.UTF_8).trimStart()
        if (headerText.startsWith("<svg", ignoreCase = true) || headerText.contains("<svg", ignoreCase = true)) {
            return null
        }
        if (header.size >= 6) {
            val signature = String(header.copyOfRange(0, 6), StandardCharsets.US_ASCII)
            if (signature == "GIF87a" || signature == "GIF89a") return "gif"
        }
        if (header.size >= 2 &&
            header[0] == 0x42.toByte() &&
            header[1] == 0x4D.toByte()
        ) {
            return "bmp"
        }
        if (header.size >= 12) {
            val riff = String(header.copyOfRange(0, 4), StandardCharsets.US_ASCII)
            val webp = String(header.copyOfRange(8, 12), StandardCharsets.US_ASCII)
            if (riff == "RIFF" && webp == "WEBP") return "webp"
            val ftyp = String(header.copyOfRange(4, 12), StandardCharsets.US_ASCII)
            if (ftyp.contains("avif")) return "avif"
        }
        return null
    }

    private fun extensionFromUrl(url: String): String {
        return runCatching {
            URI.create(url.replace(" ", "%20")).path.substringAfterLast('.', "").lowercase()
        }.getOrDefault("")
    }

    private fun buildQueryVariants(query: String, imageIntent: Boolean): List<String> {
        val normalized = query
            .replace(Regex("[\"'`]+"), " ")
            .replace(Regex("[()\\[\\]{}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isBlank()) return emptyList()

        val variants = LinkedHashSet<String>()
        variants += normalized

        val simplified = normalized.replace(Regex("""\s*[:|,;/]\s*"""), " ").replace(Regex("\\s+"), " ").trim()
        if (simplified.isNotBlank()) variants += simplified

        val tokens = simplified.split(' ').filter { it.isNotBlank() }
        val coreTokens = tokens.filterNot { it.lowercase() in commonSearchNoiseWords }
        if (coreTokens.isNotEmpty()) {
            variants += coreTokens.joinToString(" ")
        }
        if (coreTokens.size > 4) {
            variants += coreTokens.take(4).joinToString(" ")
        }

        if (imageIntent) {
            variants += "$normalized photo"
            variants += "$normalized image"
            variants += "$normalized official"
            if (coreTokens.isNotEmpty()) {
                val core = coreTokens.joinToString(" ")
                variants += "$core photo"
                variants += "$core event"
            }
        } else {
            variants += "$normalized overview"
            if (coreTokens.isNotEmpty()) {
                variants += coreTokens.take(5).joinToString(" ")
            }
        }

        return variants
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(8)
    }

    companion object {
        private val supportedImageExtensions = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "avif")
        private val blockedDocumentExtensions = setOf("pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx")
        private const val MAX_IMAGE_SEED_RESULTS = 6
        private const val MAX_IMAGE_PAGE_FETCHES = 5
        private val commonSearchNoiseWords = setOf(
            "the", "and", "for", "with", "from", "into", "about", "overview",
            "это", "как", "что", "для", "про", "или", "обзор", "стратегия", "инновации"
        )
    }
}

fun main() {
    val di = DI.invoke { import(mainDiModule) }
    val filesToolUtil: FilesToolUtil by di.instance()
    val tool = ToolWebResearch(filesToolUtil)

    val result = tool.invoke(
        WebResearchInput(
            mode = WebResearchMode.IMAGE,
            query = "Юлий Цезарь",
            limit = 8,
            downloadImages = false,
        )
    )
    println(result)
}
