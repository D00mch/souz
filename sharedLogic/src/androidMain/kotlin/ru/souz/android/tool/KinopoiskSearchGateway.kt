package ru.souz.android.tool

import android.content.Context
import android.net.Uri
import com.fasterxml.jackson.annotation.JsonInclude
import org.slf4j.LoggerFactory

private const val SEARCH_AUTHORITY = "ru.kinopoisk.tv.search.kinopoisk.tv"
private const val COLUMN_TEXT_1 = "suggest_text_1"
private const val COLUMN_TEXT_2 = "suggest_text_2"
private const val COLUMN_INTENT_DATA = "suggest_intent_data"
private const val COLUMN_INTENT_DATA_ID = "suggest_intent_data_id"
private const val COLUMN_RATING_SCORE = "suggest_rating_score"

/**
 * Kinopoisk exposes no search intent or deep link; the only search surface is its Android TV
 * suggestion provider, which takes the query through `selectionArgs`.
 */
class KinopoiskSearchGateway(context: Context) {
    private val l = LoggerFactory.getLogger(KinopoiskSearchGateway::class.java)
    private val appContext = context.applicationContext
    private val suggestUri: Uri = Uri.parse("content://$SEARCH_AUTHORITY/search_suggest_query")

    fun search(query: String, limit: Int): List<KinopoiskSuggestion> {
        val cursor = runCatching {
            appContext.contentResolver.query(suggestUri, null, null, arrayOf(query), null)
        }.getOrElse {
            l.warn("Kinopoisk suggestion query failed: {}", it.message)
            throw IllegalStateException("Kinopoisk search is unavailable: ${it.message}")
        } ?: return emptyList()

        return cursor.use {
            l.info("Kinopoisk suggestions for '{}': rows={} columns={}", query, it.count, it.columnNames.toList())
            buildList {
                while (it.moveToNext() && size < limit) {
                    val row = (0 until it.columnCount).associate { index ->
                        it.getColumnName(index) to runCatching { it.getString(index) }.getOrNull().orEmpty()
                    }.filterValues(String::isNotBlank)
                    add(row.toSuggestion())
                }
            }
        }
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class KinopoiskSuggestion(
    val title: String?,
    /** Original title for foreign releases; absent for Russian ones. */
    val originalTitle: String?,
    val rating: String?,
    val deeplink: String?,
    val filmId: String?,
    /** Kept only when no deep link was resolved, so unexpected provider schemas stay diagnosable. */
    val raw: Map<String, String>?,
)

private fun Map<String, String>.toSuggestion(): KinopoiskSuggestion {
    val deeplink = resolveDeeplink()
    return KinopoiskSuggestion(
        title = this[COLUMN_TEXT_1],
        originalTitle = this[COLUMN_TEXT_2],
        rating = this[COLUMN_RATING_SCORE],
        deeplink = deeplink,
        filmId = deeplink?.let(::filmIdFrom),
        raw = if (deeplink == null) this else null,
    )
}

private fun Map<String, String>.resolveDeeplink(): String? {
    val data = this[COLUMN_INTENT_DATA] ?: return null
    val id = this[COLUMN_INTENT_DATA_ID]
    return if (id.isNullOrBlank()) data else "${data.trimEnd('/')}/$id"
}

private fun filmIdFrom(deeplink: String): String? = runCatching {
    val uri = Uri.parse(deeplink)
    uri.getQueryParameter("filmId") ?: uri.lastPathSegment?.takeIf { it.all(Char::isDigit) }
}.getOrNull()
