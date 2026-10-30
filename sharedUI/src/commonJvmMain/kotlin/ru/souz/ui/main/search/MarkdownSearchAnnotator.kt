package ru.souz.ui.main.search

import androidx.compose.ui.text.SpanStyle
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.DefaultMarkdownAnnotator
import com.mikepenz.markdown.utils.MARKDOWN_TAG_URL
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.MarkdownElementTypes

fun searchMarkdownAnnotator(
    projection: MarkdownTextSearchPartProjection,
    matchRanges: List<SearchTextRange>,
    highlightColor: androidx.compose.ui.graphics.Color,
    activeHighlightColor: androidx.compose.ui.graphics.Color = highlightColor,
    activeRange: SearchTextRange? = null,
    linkSpanStyle: SpanStyle? = null,
): MarkdownAnnotator {
    val sortedRanges = matchRanges.sortedBy { it.start }

    return DefaultMarkdownAnnotator annotate@{ content: String, child: ASTNode ->
        val leafText = when (child.type) {
            MarkdownTokenTypes.WHITE_SPACE -> " "
            MarkdownTokenTypes.EMPH -> {
                val parentType = child.parent?.type
                if (parentType != MarkdownElementTypes.EMPH && parentType != MarkdownElementTypes.STRONG) {
                    "*"
                } else {
                    null
                }
            }
            else -> content.markdownVisibleLeafText(child)
        } ?: return@annotate false

        // Cells may recompose independently and in any order. Source offsets remain stable.
        val textRange = projection.leafRanges[child.startOffset] ?: return@annotate false
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
        true
    }
}
