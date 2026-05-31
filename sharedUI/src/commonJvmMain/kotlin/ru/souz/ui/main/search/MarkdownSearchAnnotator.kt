package ru.souz.ui.main.search

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.SpanStyle
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.utils.MARKDOWN_TAG_URL
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.MarkdownElementTypes

@Composable
fun rememberSearchMarkdownAnnotator(
    matchRanges: List<SearchTextRange>,
    highlightColor: androidx.compose.ui.graphics.Color,
    activeHighlightColor: androidx.compose.ui.graphics.Color = highlightColor,
    activeRange: SearchTextRange? = null,
    linkSpanStyle: SpanStyle? = null,
): MarkdownAnnotator {
    val sortedRanges = matchRanges.sortedBy { it.start }
    var currentOffset = 0

    return markdownAnnotator { content: String, child: ASTNode ->
        val leafText = when (child.type) {
            MarkdownTokenTypes.WHITE_SPACE if currentOffset > 0 -> " "
            MarkdownTokenTypes.EMPH -> {
                val parentType = child.parent?.type
                if (parentType != MarkdownElementTypes.EMPH && parentType != MarkdownElementTypes.STRONG) {
                    "*"
                } else {
                    null
                }
            }
            else -> content.markdownVisibleLeafText(child)
        } ?: return@markdownAnnotator false

        val textRange = SearchTextRange(
            start = currentOffset,
            endExclusive = currentOffset + leafText.length,
        )
        if (child.isMarkdownAutoLinkNode() && linkSpanStyle != null) {
            val destination = content.markdownAutoLinkDestination(child)
            pushStringAnnotation(MARKDOWN_TAG_URL, destination)
            pushStyle(linkSpanStyle)
            appendSearchHighlightedText(
                text = leafText,
                textRange = textRange,
                matchRanges = sortedRanges,
                highlightColor = highlightColor,
                activeHighlightColor = activeHighlightColor,
                activeRange = activeRange,
            )
            pop()
            pop()
        } else {
            appendSearchHighlightedText(
                text = leafText,
                textRange = textRange,
                matchRanges = sortedRanges,
                highlightColor = highlightColor,
                activeHighlightColor = activeHighlightColor,
                activeRange = activeRange,
            )
        }
        currentOffset = textRange.endExclusive
        true
    }
}
