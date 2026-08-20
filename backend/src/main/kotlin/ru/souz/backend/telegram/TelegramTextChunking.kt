package ru.souz.backend.telegram

/** Telegram's hard limit on a single sendMessage's text length. */
internal const val TELEGRAM_TEXT_LIMIT: Int = 4_096

/**
 * Splits [text] into pieces no longer than [maxLength], preferring to break on a newline or space
 * near the limit so chunks don't cut mid-word. Blank text is returned as a single (blank) chunk —
 * callers that need a non-blank fallback (e.g. "Готово.") must substitute before calling this.
 */
internal fun telegramTextChunks(
    text: String,
    maxLength: Int = TELEGRAM_TEXT_LIMIT,
): List<String> {
    if (text.length <= maxLength) {
        return listOf(text)
    }
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        val remaining = text.length - start
        if (remaining <= maxLength) {
            chunks += text.substring(start)
            break
        }
        val hardEnd = start + maxLength
        val splitAt = text.lastIndexOf('\n', hardEnd - 1, ignoreCase = false)
            .takeIf { it >= start + maxLength / 2 }
            ?: text.lastIndexOf(' ', hardEnd - 1, ignoreCase = false)
                .takeIf { it >= start + maxLength / 2 }
            ?: hardEnd
        val endExclusive = if (splitAt == hardEnd) hardEnd else splitAt + 1
        chunks += text.substring(start, endExclusive)
        start = endExclusive
    }
    return chunks
}
