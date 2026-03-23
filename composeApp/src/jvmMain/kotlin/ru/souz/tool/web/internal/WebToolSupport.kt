package ru.souz.tool.web.internal

import ru.souz.tool.BadInputException

internal class WebToolSupport {
    val userAgent: String =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36"

    val acceptHeader: String = "text/html,application/json;q=0.9,*/*;q=0.8"

    fun requireWebQuery(raw: String): String {
        val query = raw.trim()
        if (query.isBlank()) throw BadInputException("`query` is required")
        return query
    }

    fun requireHttpUrl(raw: String): String {
        val url = raw.trim()
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            throw BadInputException("`url` must start with http:// or https://")
        }
        return url
    }

    fun toSafeHttpUrl(raw: String): String = raw.replace(" ", "%20")
}
