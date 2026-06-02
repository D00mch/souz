package ru.souz.ambient

import java.util.Locale

internal object AmbientTaskTextSanitizer {
    fun normalize(taskText: String, suggestionText: String? = null): String {
        val task = taskText.trim()
        if (task.isBlank()) return ""
        if (!task.looksLikeToolSyntax()) return task

        return task.extractQueryArgument()
            ?: suggestionText?.toCommandText()?.takeUnless { it.looksLikeToolSyntax() }
            ?: task.calendarFallback()
            ?: ""
    }

    private fun String.extractQueryArgument(): String? =
        QUERY_ARGUMENT.find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.ifBlank { null }

    private fun String.calendarFallback(): String? {
        if (!contains("Calendar", ignoreCase = true)) return null
        val date = DATE_ARGUMENT.find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.lowercase(Locale.ROOT)
        return when (date) {
            "tomorrow" -> "проверь календарь на завтра"
            "today" -> "проверь календарь на сегодня"
            else -> "проверь календарь"
        }
    }

    private fun String.toCommandText(): String =
        trim()
            .removePrefix("Похоже, я могу помочь:")
            .trim()
            .trimEnd('?', '.', '!', ' ')

    private fun String.looksLikeToolSyntax(): Boolean =
        TOOL_INVOCATION.matches(this) ||
            TOOL_ID.matches(this) ||
            CATEGORY_TOOL_ID.matches(this)

    private val QUERY_ARGUMENT = Regex("""\bquery\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val DATE_ARGUMENT = Regex("""\bdate\s*[:=]\s*["']?([A-Za-z_-]+)["']?""", RegexOption.IGNORE_CASE)
    private val TOOL_INVOCATION = Regex("""^[A-Za-z][A-Za-z0-9_]*\s*[\({].*""")
    private val TOOL_ID = Regex("""^tool:[A-Za-z_]+:[A-Za-z][A-Za-z0-9_]*.*""", RegexOption.IGNORE_CASE)
    private val CATEGORY_TOOL_ID = Regex("""^[A-Z_]+:[A-Za-z][A-Za-z0-9_]*.*""")
}
