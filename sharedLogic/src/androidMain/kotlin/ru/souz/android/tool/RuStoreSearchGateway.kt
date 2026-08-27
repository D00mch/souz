package ru.souz.android.tool

import android.content.Context
import android.database.Cursor
import android.net.Uri
import com.fasterxml.jackson.annotation.JsonInclude
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory

private const val SEARCH_AUTHORITY = "rustore.search"
private const val SUGGEST_PATH = "search_suggest_query"
private const val COLUMN_TEXT_1 = "suggest_text_1"
private const val COLUMN_TEXT_2 = "suggest_text_2"
private const val COLUMN_INTENT_DATA = "suggest_intent_data"
private const val COLUMN_INTENT_DATA_ID = "suggest_intent_data_id"
private const val QUERY_ATTEMPTS = 3
private val RETRY_DELAYS_MS = longArrayOf(700, 1_500)

/**
 * RuStore exposes no search intent or deep link, only its Android TV suggestion provider, and that
 * one is guarded by GLOBAL_SEARCH. The app must be platform signed or privileged and declare the
 * permission; otherwise every query fails with a SecurityException.
 */
class RuStoreSearchGateway(context: Context) {
    private val l = LoggerFactory.getLogger(RuStoreSearchGateway::class.java)
    private val appContext = context.applicationContext

    suspend fun search(query: String, limit: Int): List<RuStoreSuggestion> {
        var lastError: Throwable? = null
        repeat(QUERY_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(RETRY_DELAYS_MS[attempt - 1])
            runCatching { queryProvider(query) }
                .onSuccess { cursor -> return cursor?.let { read(it, query, limit) } ?: emptyList() }
                .onFailure { error ->
                    lastError = error
                    l.warn("RuStore suggestion query failed (attempt {}): {}", attempt + 1, error.message)
                }
        }
        throw IllegalStateException("RuStore search is unavailable: ${lastError?.message}")
    }

    /**
     * Providers differ in how they take the term: Kinopoisk wants it in selectionArgs, others read
     * it from the last path segment. Try both before giving up.
     */
    private fun queryProvider(query: String): Cursor? {
        val resolver = appContext.contentResolver
        val bySelection = Uri.parse("content://$SEARCH_AUTHORITY/$SUGGEST_PATH")
        return runCatching { resolver.query(bySelection, null, null, arrayOf(query), null) }
            .getOrElse { selectionError ->
                if (selectionError is SecurityException) throw selectionError
                l.debug("selectionArgs form rejected, trying path form: {}", selectionError.message)
                val byPath = bySelection.buildUpon().appendPath(query).build()
                resolver.query(byPath, null, null, null, null)
            }
    }

    private fun read(cursor: Cursor, query: String, limit: Int): List<RuStoreSuggestion> =
        cursor.use {
            l.info("RuStore suggestions for '{}': rows={} columns={}", query, it.count, it.columnNames.toList())
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

@JsonInclude(JsonInclude.Include.NON_NULL)
data class RuStoreSuggestion(
    val title: String?,
    val subtitle: String?,
    val packageName: String?,
    val deeplink: String?,
    /** Kept only when no deep link was resolved, so unexpected provider schemas stay diagnosable. */
    val raw: Map<String, String>?,
)

private fun Map<String, String>.toSuggestion(): RuStoreSuggestion {
    val deeplink = resolveDeeplink()
    return RuStoreSuggestion(
        title = this[COLUMN_TEXT_1],
        subtitle = this[COLUMN_TEXT_2],
        packageName = deeplink?.let(::packageNameFrom) ?: values.firstOrNull(::looksLikePackageName),
        deeplink = deeplink,
        raw = if (deeplink == null) this else null,
    )
}

private fun Map<String, String>.resolveDeeplink(): String? {
    val data = this[COLUMN_INTENT_DATA] ?: return null
    val id = this[COLUMN_INTENT_DATA_ID]
    return if (id.isNullOrBlank()) data else "${data.trimEnd('/')}/$id"
}

private fun packageNameFrom(deeplink: String): String? = runCatching {
    val uri = Uri.parse(deeplink)
    uri.getQueryParameter("id") ?: uri.lastPathSegment?.takeIf(::looksLikePackageName)
}.getOrNull()

private fun looksLikePackageName(value: String): Boolean =
    value.count { it == '.' } >= 2 && value.none(Char::isWhitespace) && value.all { it.isLetterOrDigit() || it == '.' || it == '_' }
