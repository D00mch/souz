package ru.souz.ui.common

import androidx.compose.ui.text.style.TextAlign
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

internal data class ChatMarkdownTable(
    val alignments: List<TextAlign>,
    val rows: List<List<ASTNode?>>,
)

internal fun ASTNode.chatMarkdownTable(content: String): ChatMarkdownTable {
    val rows = children.filter { it.type == GFMElementTypes.HEADER || it.type == GFMElementTypes.ROW }
        .map { row -> row.children.filter { it.type == GFMTokenTypes.CELL } }
    val columnCount = rows.first().size
    val separator = children.first { it.type == GFMTokenTypes.TABLE_SEPARATOR }
        .getTextInNode(content).toString().trim().removePrefix("|").removeSuffix("|")
    val alignments = separator.split('|').take(columnCount).map { cell ->
        val delimiter = cell.trim()
        when {
            delimiter.startsWith(':') && delimiter.endsWith(':') -> TextAlign.Center
            delimiter.endsWith(':') -> TextAlign.Right
            else -> TextAlign.Left
        }
    }
    return ChatMarkdownTable(
        alignments = alignments,
        // GFM ignores excess body cells and fills missing cells with empty content.
        rows = rows.map { cells -> List(columnCount) { cells.getOrNull(it) } },
    )
}

internal fun ASTNode.tableCellChildren(): List<ASTNode> = children
    .dropWhile { it.type == MarkdownTokenTypes.WHITE_SPACE }
    .dropLastWhile { it.type == MarkdownTokenTypes.WHITE_SPACE }
