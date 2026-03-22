package ru.souz.tool.presentation

import com.fasterxml.jackson.databind.ObjectMapper
import org.jsoup.Jsoup
import ru.souz.giga.gigaJsonMapper
import ru.souz.tool.BadInputException
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.min

private const val WEB_RESEARCH_CONNECT_TIMEOUT_SECONDS = 6L
private const val WEB_RESEARCH_MIN_REQUEST_INTERVAL_MILLIS = 1_200L
private const val WEB_RESEARCH_INITIAL_RETRY_DELAY_MILLIS = 2_000L
private const val WEB_RESEARCH_MAX_RETRY_DELAY_MILLIS = 12_000L
private const val WEB_RESEARCH_MAX_RETRIES = 2

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

/**
 * Shared web research engine used by:
 * - [ToolWebSearch]
 * - [ToolWebImageSearch]
 * - [ToolWebPageText]
 *
 * This keeps search/parsing heuristics and HTTP behavior in one place while tool contracts stay simple.
 */
class WebResearchClient(
    private val mapper: ObjectMapper = gigaJsonMapper,
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .connectTimeout(Duration.ofSeconds(WEB_RESEARCH_CONNECT_TIMEOUT_SECONDS))
        .build(),
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val sleepMillis: (Long) -> Unit = Thread::sleep,
    private val requestSender: ((HttpRequest) -> HttpResponse<String>)? = null,
) {
    private val requestPacingLock = Any()
    private var nextRequestAtMillis: Long = 0L

    fun searchWeb(query: String, limit: Int): List<WebSearchResult> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) throw BadInputException("`query` is required")
        val targetCount = limit.coerceIn(1, 20)
        val aggregated = LinkedHashMap<String, WebSearchResult>()
        for (variant in buildQueryVariants(normalizedQuery, imageIntent = false).take(4)) {
            runCatching { searchWebSingleQuery(variant, targetCount) }
                .getOrDefault(emptyList())
                .forEach { result ->
                    aggregated.putIfAbsent(result.url, result)
                }
            if (aggregated.size >= targetCount) break
        }
        return aggregated.values.take(targetCount).toList()
    }

    fun searchImages(query: String, limit: Int): List<WebImageResult> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) throw BadInputException("`query` is required")
        val targetCount = limit.coerceIn(1, 20)
        val aggregated = LinkedHashMap<String, WebImageResult>()
        val fetchLimit = min(16, maxOf(targetCount * 2, 8))
        for (variant in buildQueryVariants(normalizedQuery, imageIntent = true).take(4)) {
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
        return aggregated.values.take(targetCount).toList()
    }

    fun extractPageText(url: String, maxChars: Int): String {
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            throw BadInputException("`url` must start with http:// or https://")
        }
        val html = httpGet(url)
        val doc = Jsoup.parse(html)
        doc.select("script, style, noscript, svg").remove()
        val normalized = doc.text().replace(Regex("\\s+"), " ").trim()
        return normalized.take(maxChars.coerceIn(500, 20_000))
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

    private fun decodeDuckDuckGoRedirect(rawHref: String): String {
        if (rawHref.isBlank()) return ""
        if (rawHref.startsWith("http://") || rawHref.startsWith("https://")) return rawHref

        val normalized = when {
            rawHref.startsWith("//") -> "https:$rawHref"
            rawHref.startsWith("/") -> "https://duckduckgo.com$rawHref"
            else -> rawHref
        }
        if (normalized.contains("/y.js?", ignoreCase = true) || normalized.contains("ad_domain=", ignoreCase = true)) {
            return ""
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
            .header("User-Agent", DEFAULT_WEB_USER_AGENT)
            .header("Accept", "text/html,application/json;q=0.9,*/*;q=0.8")
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .build()

        var attempt = 0
        var retryDelayMillis = WEB_RESEARCH_INITIAL_RETRY_DELAY_MILLIS
        while (true) {
            waitForRequestSlot()
            val response = try {
                requestSender?.invoke(request)
                    ?: client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw BadInputException("Web request interrupted for $url")
            } catch (e: HttpTimeoutException) {
                if (attempt >= WEB_RESEARCH_MAX_RETRIES) {
                    throw BadInputException("HTTP request timed out for $url")
                }
                sleepForMillis(retryDelayMillis)
                retryDelayMillis = (retryDelayMillis * 2).coerceAtMost(WEB_RESEARCH_MAX_RETRY_DELAY_MILLIS)
                attempt += 1
                continue
            } catch (e: IOException) {
                if (attempt >= WEB_RESEARCH_MAX_RETRIES) {
                    val message = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                    throw BadInputException("HTTP request failed for $url: $message")
                }
                sleepForMillis(retryDelayMillis)
                retryDelayMillis = (retryDelayMillis * 2).coerceAtMost(WEB_RESEARCH_MAX_RETRY_DELAY_MILLIS)
                attempt += 1
                continue
            }

            if (response.statusCode() < 400) {
                return response.body()
            }

            if (response.statusCode() in retryableStatusCodes && attempt < WEB_RESEARCH_MAX_RETRIES) {
                val retryAfterDelayMillis = parseRetryAfterMillis(
                    response.headers().firstValue("Retry-After").orElse(null)
                )
                val delayMillis = maxOf(retryDelayMillis, retryAfterDelayMillis ?: 0L)
                    .coerceAtMost(WEB_RESEARCH_MAX_RETRY_DELAY_MILLIS)
                sleepForMillis(delayMillis)
                retryDelayMillis = (retryDelayMillis * 2).coerceAtMost(WEB_RESEARCH_MAX_RETRY_DELAY_MILLIS)
                attempt += 1
                continue
            }

            val bodyPreview = response.body().take(min(600, response.body().length))
            throw BadInputException("HTTP ${response.statusCode()} for $url: $bodyPreview")
        }
    }

    private fun waitForRequestSlot() {
        val delayMillis = synchronized(requestPacingLock) {
            val now = currentTimeMillis()
            val scheduledAt = maxOf(now, nextRequestAtMillis)
            nextRequestAtMillis = scheduledAt + WEB_RESEARCH_MIN_REQUEST_INTERVAL_MILLIS
            scheduledAt - now
        }
        sleepForMillis(delayMillis)
    }

    private fun sleepForMillis(delayMillis: Long) {
        if (delayMillis <= 0) return
        try {
            sleepMillis(delayMillis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw BadInputException("Interrupted while waiting before next web request")
        }
    }

    private fun parseRetryAfterMillis(value: String?): Long? {
        val normalized = value?.trim().orEmpty()
        if (normalized.isBlank()) return null
        normalized.toLongOrNull()?.let { seconds ->
            return (seconds.coerceAtLeast(0L) * 1_000L)
        }
        return runCatching {
            val retryAtMillis = ZonedDateTime.parse(normalized, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant()
                .toEpochMilli()
            (retryAtMillis - currentTimeMillis()).coerceAtLeast(0L)
        }.getOrNull()
    }

    private fun toSafeUri(url: String): URI = URI.create(url.replace(" ", "%20"))

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
        private const val DEFAULT_WEB_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36"
        private val retryableStatusCodes = setOf(429, 500, 502, 503, 504)
        private val blockedDocumentExtensions = setOf("pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx")
        private const val MAX_IMAGE_SEED_RESULTS = 6
        private const val MAX_IMAGE_PAGE_FETCHES = 5
        private val commonSearchNoiseWords = setOf(
            "the", "and", "for", "with", "from", "into", "about", "overview",
            "это", "как", "что", "для", "про", "или", "обзор", "стратегия", "инновации"
        )
    }
}
