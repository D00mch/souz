package ru.souz.ui.main.search

import com.mikepenz.markdown.utils.EntityConverter
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser

internal fun String.buildMarkdownSearchableText(): String {
    val markdown: String = this
    if (markdown.isBlank()) return ""
    val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(markdown)
    val searchableText = StringBuilder()
    markdown.appendMarkdownSearchableText(
        children = tree.children,
        searchableText = searchableText,
    )
    return searchableText.toString()
}

internal fun String.markdownVisibleLeafText(
    node: ASTNode,
): String? = when (node.type) {
    MarkdownElementTypes.AUTOLINK -> markdownAutoLinkText(this, node)
    MarkdownTokenTypes.TEXT -> node.getUnescapedTextInNode(this)
    MarkdownTokenTypes.SINGLE_QUOTE -> "'"
    MarkdownTokenTypes.DOUBLE_QUOTE -> "\""
    MarkdownTokenTypes.LPAREN -> "("
    MarkdownTokenTypes.RPAREN -> ")"
    MarkdownTokenTypes.LBRACKET -> "["
    MarkdownTokenTypes.RBRACKET -> "]"
    MarkdownTokenTypes.LT -> "<"
    MarkdownTokenTypes.GT -> ">"
    MarkdownTokenTypes.COLON -> ":"
    MarkdownTokenTypes.EXCLAMATION_MARK -> "!"
    MarkdownTokenTypes.BACKTICK -> "`"
    MarkdownTokenTypes.HARD_LINE_BREAK -> "\n\n"
    MarkdownTokenTypes.EOL -> "\n"
    GFMTokenTypes.GFM_AUTOLINK -> node.getUnescapedTextInNode(this)
    else -> null
}

internal fun ASTNode.isMarkdownAutoLinkNode(): Boolean =
    this.type == MarkdownElementTypes.AUTOLINK ||
            (this.type == GFMTokenTypes.GFM_AUTOLINK && this.parent?.type != MarkdownElementTypes.LINK_TEXT)

fun String.markdownAutoLinkDestination(
    node: ASTNode,
): String = markdownAutoLinkText(this, node)

private fun String.appendMarkdownSearchableText(
    children: List<ASTNode>,
    searchableText: StringBuilder,
) {
    val content = this
    var skipIfNext: IElementType? = null
    children.forEach { child ->
        if (skipIfNext == child.type) {
            skipIfNext = null
            return@forEach
        }

        val leafText = when (child.type) {
            MarkdownTokenTypes.WHITE_SPACE if searchableText.isNotEmpty() -> " "
            MarkdownTokenTypes.EMPH -> markdownEmphasisTokenText(child)
            else -> content.markdownVisibleLeafText(child)
        }
        if (leafText != null) {
            searchableText.append(leafText)
            return@forEach
        }

        when (child.type) {
            MarkdownElementTypes.PARAGRAPH,
            MarkdownElementTypes.EMPH,
            MarkdownElementTypes.STRONG,
            MarkdownElementTypes.BLOCK_QUOTE,
            MarkdownElementTypes.ORDERED_LIST,
            MarkdownElementTypes.UNORDERED_LIST,
            MarkdownElementTypes.LIST_ITEM,
            MarkdownElementTypes.CODE_BLOCK,
            MarkdownElementTypes.CODE_FENCE,
            MarkdownElementTypes.ATX_1,
            MarkdownElementTypes.ATX_2,
            MarkdownElementTypes.ATX_3,
            MarkdownElementTypes.ATX_4,
            MarkdownElementTypes.ATX_5,
            MarkdownElementTypes.ATX_6,
            MarkdownElementTypes.SETEXT_1,
            MarkdownElementTypes.SETEXT_2,
            GFMElementTypes.STRIKETHROUGH ->
                content.appendMarkdownSearchableText(child.children, searchableText)

            MarkdownElementTypes.CODE_SPAN ->
                content.appendMarkdownSearchableText(
                    children = child.children.innerList(),
                    searchableText = searchableText,
                )

            MarkdownElementTypes.INLINE_LINK,
            MarkdownElementTypes.SHORT_REFERENCE_LINK,
            MarkdownElementTypes.FULL_REFERENCE_LINK -> {
                val linkText = child.findChildOfType(MarkdownElementTypes.LINK_TEXT)?.children?.innerList()
                if (linkText != null) {
                    content.appendMarkdownSearchableText(linkText, searchableText)
                }
            }

            MarkdownElementTypes.IMAGE,
            MarkdownElementTypes.LINK_DEFINITION -> Unit

            MarkdownTokenTypes.BLOCK_QUOTE -> {
                skipIfNext = MarkdownTokenTypes.WHITE_SPACE
            }

            else -> content.appendMarkdownSearchableText(child.children, searchableText)
        }
    }
}

private fun markdownAutoLinkText(
    content: String,
    node: ASTNode,
): String {
    val targetNode = node.children.firstOrNull { it.type.name == MarkdownElementTypes.AUTOLINK.name } ?: node
    return targetNode.getUnescapedTextInNode(content)
}

private fun markdownEmphasisTokenText(node: ASTNode): String? {
    val parentType = node.parent?.type
    return if (parentType != MarkdownElementTypes.EMPH && parentType != MarkdownElementTypes.STRONG) {
        "*"
    } else {
        null
    }
}

private fun List<ASTNode>.innerList(): List<ASTNode> =
    if (size <= 2) emptyList() else subList(1, size - 1)

private fun ASTNode.getUnescapedTextInNode(allFileText: CharSequence): String {
    val escapedText = getTextInNode(allFileText).toString()
    return EntityConverter.replaceEntities(
        text = escapedText,
        processEntities = false,
        processEscapes = true,
    )
}
