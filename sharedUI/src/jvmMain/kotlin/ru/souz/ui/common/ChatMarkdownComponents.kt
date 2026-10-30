package ru.souz.ui.common

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownComponents
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.LocalMarkdownTypography
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownText
import com.mikepenz.markdown.utils.buildMarkdownAnnotatedString
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode

@Composable
internal fun chatMarkdownComponents() = markdownComponents(custom = { type, model ->
    if (type == GFMElementTypes.TABLE) {
        ChatMarkdownTableContent(model)
    } else {
        // A non-null custom hook consumes unknown nodes in renderer 0.27, including LIST_ITEM.
        // Preserve its default recursive dispatch rather than swallowing ordinary list contents.
        model.node.children.forEach { child ->
            ChatMarkdownElement(model.content, child, includeSpacer = type != MarkdownElementTypes.LIST_ITEM)
        }
    }
})

@Composable
private fun ColumnScope.ChatMarkdownElement(content: String, node: ASTNode, includeSpacer: Boolean) {
    val components = LocalMarkdownComponents.current
    val component = when (node.type) {
        MarkdownTokenTypes.TEXT -> components.text
        MarkdownTokenTypes.EOL -> components.eol
        MarkdownElementTypes.CODE_FENCE -> components.codeFence
        MarkdownElementTypes.CODE_BLOCK -> components.codeBlock
        MarkdownElementTypes.ATX_1 -> components.heading1
        MarkdownElementTypes.ATX_2 -> components.heading2
        MarkdownElementTypes.ATX_3 -> components.heading3
        MarkdownElementTypes.ATX_4 -> components.heading4
        MarkdownElementTypes.ATX_5 -> components.heading5
        MarkdownElementTypes.ATX_6 -> components.heading6
        MarkdownElementTypes.SETEXT_1 -> components.setextHeading1
        MarkdownElementTypes.SETEXT_2 -> components.setextHeading2
        MarkdownElementTypes.BLOCK_QUOTE -> components.blockQuote
        MarkdownElementTypes.PARAGRAPH -> components.paragraph
        MarkdownElementTypes.ORDERED_LIST -> components.orderedList
        MarkdownElementTypes.UNORDERED_LIST -> components.unorderedList
        MarkdownElementTypes.IMAGE -> components.image
        MarkdownElementTypes.LINK_DEFINITION -> components.linkDefinition
        MarkdownTokenTypes.HORIZONTAL_RULE -> components.horizontalRule
        else -> null
    }
    if (includeSpacer) Spacer(Modifier.height(LocalMarkdownPadding.current.block))
    val model = MarkdownComponentModel(content, node, LocalMarkdownTypography.current)
    if (component != null) component(model) else components.custom?.invoke(this, node.type, model)
}

@Composable
private fun ChatMarkdownTableContent(model: MarkdownComponentModel) {
    val table = remember(model.content, model.node) { model.node.chatMarkdownTable(model.content) }
    val style = LocalMarkdownTypography.current.paragraph
    val borderColor = LocalMarkdownColors.current.dividerColor
    val cells = table.rows.mapIndexed { rowIndex, row ->
        row.map { cell ->
            buildAnnotatedString {
                if (rowIndex == 0) pushStyle(style.copy(fontWeight = FontWeight.SemiBold).toSpanStyle())
                if (cell != null) buildMarkdownAnnotatedString(model.content, cell.tableCellChildren())
                if (rowIndex == 0) pop()
            }
        }
    }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val columnWidths = table.alignments.indices.map { column ->
        val widthPx = cells.maxOf { row -> measurer.measure(row[column], style = style, softWrap = false).size.width }
        with(density) { widthPx.toDp() } + 24.dp
    }
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.horizontalScroll(scroll)) {
            cells.forEach { row ->
                Row(Modifier.height(IntrinsicSize.Min)) {
                    row.forEachIndexed { column, cell ->
                        Box(
                            Modifier.width(columnWidths[column]).fillMaxHeight()
                                .border(0.5.dp, borderColor)
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            MarkdownText(
                                content = cell,
                                style = style.copy(textAlign = table.alignments[column]),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
        if (scroll.maxValue > 0) {
            HorizontalScrollbar(
                adapter = rememberScrollbarAdapter(scroll),
                modifier = Modifier.fillMaxWidth().height(10.dp),
            )
        }
    }
}
