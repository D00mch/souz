package ru.souz.tool.web.internal

internal data class WebSearchResult(
    val title: String,
    val url: String,
    val snippet: String,
)

internal data class WebImageResult(
    val title: String,
    val imageUrl: String,
    val pageUrl: String?,
    val thumbnailUrl: String?,
    val license: String?,
    val localPath: String?,
)
