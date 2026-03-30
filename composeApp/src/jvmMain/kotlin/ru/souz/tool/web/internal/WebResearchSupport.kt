package ru.souz.tool.web.internal

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import ru.souz.llms.restJsonMapper
import ru.souz.tool.BadInputException
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.math.min

private const val WEB_RESEARCH_MIN_REQUEST_INTERVAL_MILLIS = 1_200L
private const val WEB_RESEARCH_MAX_PACED_HOSTS = 64
private const val WEB_RESEARCH_MAX_SEARCH_VARIANTS = 2
private const val WEB_RESEARCH_MAX_IMAGE_VARIANTS = 2
private const val WEB_RESEARCH_MAX_UNAVAILABLE_VARIANTS_BEFORE_ABORT = 2

internal enum class WebSearchProviderFailureKind {
    BLOCKED,
    UNAVAILABLE,
}

internal class WebSearchProviderException(
    val kind: WebSearchProviderFailureKind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Shared web research engine used by:
 * - [ToolInternetSearch]
 * - [ToolInternetResearch]
 * - [ToolWebImageSearch]
 * - [ToolWebPageText]
 *
 * This keeps search/parsing heuristics and HTTP behavior in one place while tool contracts stay simple.
 */
internal class WebResearchClient(
    private val mapper: ObjectMapper = restJsonMapper,
    private val httpGet: suspend (String, Long, Boolean) -> WebTextResponse = { url, timeoutMillis, retry ->
        WebHttpSupport().getText(url, timeoutMillis, retry = retry)
    },
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val sleepMillis: suspend (Long) -> Unit = { delay(it) },
    private val webToolSupport: WebToolSupport = WebToolSupport(),
) {
    private val logger = LoggerFactory.getLogger(WebResearchClient::class.java)
    private val requestPacingLock = Any()
    private val nextRequestAtMillisByHost = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > WEB_RESEARCH_MAX_PACED_HOSTS
        }
    }

    suspend fun searchWeb(query: String, limit: Int): List<WebSearchResult> {
        val normalizedQuery = webToolSupport.requireWebQuery(query)
        val targetCount = limit.coerceIn(1, 20)
        val aggregated = LinkedHashMap<String, WebSearchResult>()
        var unavailableFailures = 0
        for (variant in buildQueryVariants(normalizedQuery, imageIntent = false).take(WEB_RESEARCH_MAX_SEARCH_VARIANTS)) {
            val results = try {
                searchDuckDuckGo(variant, targetCount)
            } catch (e: CancellationException) {
                throw e
            } catch (e: WebSearchProviderException) {
                logger.warn("Web search failed for query '{}': {}", variant, e.message)
                when (e.kind) {
                    WebSearchProviderFailureKind.BLOCKED -> throw e
                    WebSearchProviderFailureKind.UNAVAILABLE -> {
                        unavailableFailures += 1
                        if (unavailableFailures >= WEB_RESEARCH_MAX_UNAVAILABLE_VARIANTS_BEFORE_ABORT) {
                            throw e
                        }
                        emptyList()
                    }
                }
            } catch (e: Exception) {
                unavailableFailures += 1
                logger.warn("Web search failed for query '{}': {}", variant, e.message)
                if (unavailableFailures >= WEB_RESEARCH_MAX_UNAVAILABLE_VARIANTS_BEFORE_ABORT) {
                    throw WebSearchProviderException(
                        WebSearchProviderFailureKind.UNAVAILABLE,
                        "DuckDuckGo is currently unavailable for automated search.",
                        e,
                    )
                }
                emptyList()
            }
            results.forEach { result -> aggregated.putIfAbsent(result.url, result) }
            if (results.isNotEmpty()) unavailableFailures = 0
            if (aggregated.size >= targetCount) break
        }
        return aggregated.values.take(targetCount).toList()
    }

    suspend fun searchImages(query: String, limit: Int): List<WebImageResult> {
        val normalizedQuery = webToolSupport.requireWebQuery(query)
        val targetCount = limit.coerceIn(1, 20)
        val aggregated = LinkedHashMap<String, WebImageResult>()
        val fetchLimit = min(16, maxOf(targetCount * 2, 8))
        for (variant in buildQueryVariants(normalizedQuery, imageIntent = true).take(WEB_RESEARCH_MAX_IMAGE_VARIANTS)) {
            try {
                searchCommonsImages(variant, fetchLimit).forEach { candidate ->
                    addImageCandidate(aggregated, candidate)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.debug("Commons image search failed for query '{}': {}", variant, e.message)
            }
            if (aggregated.size < targetCount) {
                try {
                    searchPageImageCandidates(variant, fetchLimit).forEach { candidate ->
                        addImageCandidate(aggregated, candidate)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.debug("Page image search failed for query '{}': {}", variant, e.message)
                }
            }
            if (aggregated.size >= targetCount) break
        }
        return aggregated.values.take(targetCount).toList()
    }

    suspend fun extractPageText(url: String, maxChars: Int): String {
        val normalizedUrl = webToolSupport.requireHttpUrl(url)
        val html = pacedGetText(normalizedUrl, timeoutMillis = 6_000L)
        val doc = Jsoup.parse(html)
        doc.select("script, style, noscript, svg").remove()
        val normalized = doc.text().replace(Regex("\\s+"), " ").trim()
        return normalized.take(maxChars.coerceIn(500, 20_000))
    }

    private suspend fun searchCommonsImages(query: String, limit: Int): List<WebImageResult> {
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

            val body = pacedGetText(url, timeoutMillis = 8_000L)
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

    private suspend fun searchPageImageCandidates(query: String, limit: Int): List<WebImageResult> {
        val pageSeeds = LinkedHashMap<String, WebSearchResult>()
        val seedQueries = buildQueryVariants(query, imageIntent = true).take(WEB_RESEARCH_MAX_IMAGE_VARIANTS)
        for (seedQuery in seedQueries) {
            searchDuckDuckGo(seedQuery, min(MAX_IMAGE_SEED_RESULTS, limit)).forEach { result ->
                if (!isLikelyHtmlPageUrl(result.url)) return@forEach
                pageSeeds.putIfAbsent(result.url, result)
            }
            if (pageSeeds.size >= limit) break
        }

        val results = mutableListOf<WebImageResult>()
        for (page in pageSeeds.values.take(min(limit, MAX_IMAGE_PAGE_FETCHES))) {
            val html = try {
                pacedGetText(page.url, timeoutMillis = 5_000L)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                continue
            }
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

    private suspend fun searchDuckDuckGo(query: String, limit: Int): List<WebSearchResult> {
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)
        var lastFailure: Exception? = null
        var lastDiagnostics: Pair<String, String>? = null
        for (endpoint in duckDuckGoEndpoints) {
            val url = "$endpoint?q=$encodedQuery"
            val html = try {
                pacedGetText(url, DUCK_DUCK_GO_SEARCH_TIMEOUT_MILLIS, retry = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                classifyDuckDuckGoFailure(url, e)?.let { failureKind ->
                    if (failureKind == WebSearchProviderFailureKind.BLOCKED) {
                        throw WebSearchProviderException(
                            kind = failureKind,
                            message = "DuckDuckGo blocked automated search requests.",
                            cause = e,
                        )
                    }
                    lastFailure = e
                    continue
                }
                lastFailure = e
                continue
            }
            duckDuckGoChallengeReason(html, url)?.let { reason ->
                throw WebSearchProviderException(
                    kind = WebSearchProviderFailureKind.BLOCKED,
                    message = "DuckDuckGo blocked automated search requests: $reason",
                )
            }
            val parsed = parseDuckDuckGoResults(html, url, limit)
            if (parsed.isNotEmpty()) return parsed
            lastDiagnostics = duckDuckGoDiagnostics(html, url)
        }
        if (lastDiagnostics != null) {
            val (title, preview) = lastDiagnostics
            logger.warn(
                "DuckDuckGo returned HTML without parsable results for '{}'; title='{}', preview='{}'",
                query,
                title,
                preview,
            )
        }
        if (lastFailure != null) {
            throw WebSearchProviderException(
                kind = WebSearchProviderFailureKind.UNAVAILABLE,
                message = "DuckDuckGo is temporarily unavailable for automated search.",
                cause = lastFailure,
            )
        }
        return emptyList()
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
            val query = java.net.URI.create(webToolSupport.toSafeHttpUrl(normalized)).rawQuery ?: return@runCatching normalized
            query.split('&').asSequence().mapNotNull { part ->
                val key = part.substringBefore('=', "")
                val value = part.substringAfter('=', "")
                if (key == "uddg") URLDecoder.decode(value, StandardCharsets.UTF_8) else null
            }.firstOrNull().orEmpty().ifBlank { normalized }
        }.getOrDefault(normalized)
    }

    private suspend fun pacedGetText(
        url: String,
        timeoutMillis: Long,
        retry: Boolean = true,
    ): String {
        val normalizedUrl = webToolSupport.requireHttpUrl(url)
        awaitRequestSlot(normalizedUrl)
        val response = httpGet(normalizedUrl, timeoutMillis, retry)
        if (response.statusCode < 400) {
            return response.body
        }
        val bodyPreview = response.body.take(min(600, response.body.length))
        throw BadInputException("HTTP ${response.statusCode} for $normalizedUrl: $bodyPreview")
    }

    private suspend fun awaitRequestSlot(url: String) {
        val uri = java.net.URI.create(webToolSupport.toSafeHttpUrl(url))
        val hostKey = uri.host?.lowercase().orEmpty()
            .ifBlank { uri.authority?.lowercase().orEmpty() }
            .ifBlank { return }
        val delayMillis = synchronized(requestPacingLock) {
            val now = currentTimeMillis()
            val scheduledAt = maxOf(now, nextRequestAtMillisByHost[hostKey] ?: 0L)
            nextRequestAtMillisByHost[hostKey] = scheduledAt + WEB_RESEARCH_MIN_REQUEST_INTERVAL_MILLIS
            scheduledAt - now
        }
        sleep(delayMillis)
    }

    private suspend fun sleep(delayMillis: Long) {
        if (delayMillis <= 0) return
        sleepMillis(delayMillis)
    }

    private fun parseDuckDuckGoResults(
        html: String,
        baseUrl: String,
        limit: Int,
    ): List<WebSearchResult> {
        val doc = Jsoup.parse(html, baseUrl)
        val results = LinkedHashMap<String, WebSearchResult>()
        val containers = doc.select(
            "div.result, article[data-testid=result], .result.results_links, .result.results_links_deep"
        )

        containers.forEach { result ->
            val link = result.selectFirst("a.result__a, .result__title a, a[data-testid=result-title-a], h2 a")
                ?: return@forEach
            val title = link.text().trim()
            val rawHref = link.attr("href").trim()
            val url = decodeDuckDuckGoRedirect(rawHref)
            if (title.isBlank() || url.isBlank()) return@forEach

            val snippet = result.selectFirst(".result__snippet, .result-snippet, [data-result=snippet]")
                ?.text()
                ?.trim()
                .orEmpty()
            results.putIfAbsent(url, WebSearchResult(title = title, url = url, snippet = snippet))
        }

        if (results.isEmpty()) {
            doc.select("a.result__a, .result__title a, a[data-testid=result-title-a]").forEach { link ->
                val title = link.text().trim()
                val url = decodeDuckDuckGoRedirect(link.attr("href").trim())
                if (title.isBlank() || url.isBlank()) return@forEach
                results.putIfAbsent(url, WebSearchResult(title = title, url = url, snippet = ""))
            }
        }

        return results.values.take(limit).toList()
    }

    private fun duckDuckGoDiagnostics(html: String, baseUrl: String): Pair<String, String> {
        val doc = Jsoup.parse(html, baseUrl)
        val title = doc.title().trim().ifBlank { "no-title" }
        val preview = doc.text()
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(DUCK_DUCK_GO_PREVIEW_LENGTH)
        return title to preview
    }

    private fun duckDuckGoChallengeReason(html: String, baseUrl: String): String? {
        val text = Jsoup.parse(html, baseUrl)
            .text()
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase()
        return when {
            "bots use duckduckgo too" in text -> "anti-bot challenge page"
            "select all squares containing a duck" in text -> "captcha challenge page"
            "please complete the following challenge" in text -> "human verification challenge"
            else -> null
        }
    }

    private fun classifyDuckDuckGoFailure(url: String, error: Exception): WebSearchProviderFailureKind? {
        val message = error.message.orEmpty().lowercase()
        if ("duckduckgo" !in url.lowercase()) return null
        return when {
            "http 403" in message || "http 429" in message -> WebSearchProviderFailureKind.BLOCKED
            "timed out" in message ||
                "http 500" in message ||
                "http 502" in message ||
                "http 503" in message ||
                "http 504" in message -> WebSearchProviderFailureKind.UNAVAILABLE
            else -> null
        }
    }

    private fun extensionFromUrl(url: String): String {
        return runCatching {
            java.net.URI.create(webToolSupport.toSafeHttpUrl(url)).path.substringAfterLast('.', "").lowercase()
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
        private const val DUCK_DUCK_GO_SEARCH_TIMEOUT_MILLIS = 8_000L
        private const val DUCK_DUCK_GO_PREVIEW_LENGTH = 240
        private val blockedDocumentExtensions = setOf("pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx")
        private val duckDuckGoEndpoints = listOf(
            "https://duckduckgo.com/html/",
            "https://html.duckduckgo.com/html/",
        )
        private const val MAX_IMAGE_SEED_RESULTS = 6
        private const val MAX_IMAGE_PAGE_FETCHES = 5
        private val commonSearchNoiseWords = setOf(
            "the", "and", "for", "with", "from", "into", "about", "overview",
            "это", "как", "что", "для", "про", "или", "обзор", "стратегия", "инновации"
        )
    }
}
