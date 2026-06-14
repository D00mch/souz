package ru.souz.ui.main.search

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

fun String.buildSearchHighlightedAnnotatedString(
    matchRanges: List<SearchTextRange>,
    highlightColor: Color,
    activeHighlightColor: Color = highlightColor,
    activeRange: SearchTextRange? = null,
): AnnotatedString = buildAnnotatedString {
    val text = this@buildSearchHighlightedAnnotatedString
    appendSearchHighlightedText(
        text = text,
        textRange = SearchTextRange(0, text.length),
        matchRanges = matchRanges,
        highlightColor = highlightColor,
        activeHighlightColor = activeHighlightColor,
        activeRange = activeRange,
    )
}

fun AnnotatedString.Builder.appendSearchHighlightedText(
    text: String,
    textRange: SearchTextRange,
    matchRanges: List<SearchTextRange>,
    highlightColor: Color,
    activeHighlightColor: Color,
    activeRange: SearchTextRange?,
) {
    if (text.isEmpty()) return
    if (matchRanges.isEmpty()) {
        append(text)
        return
    }

    var cursor = 0
    matchRanges.forEach { matchRange ->
        if (!matchRange.overlaps(textRange)) return@forEach

        val startInText = maxOf(matchRange.start, textRange.start) - textRange.start
        val endInText = minOf(matchRange.endExclusive, textRange.endExclusive) - textRange.start
        if (startInText > cursor) {
            append(text.substring(cursor, startInText))
        }

        pushStyle(
            SpanStyle(
                background = if (matchRange == activeRange) {
                    activeHighlightColor
                } else {
                    highlightColor
                }
            )
        )
        append(text.substring(startInText, endInText))
        pop()
        cursor = endInText
    }

    if (cursor < text.length) {
        append(text.substring(cursor))
    }
}
