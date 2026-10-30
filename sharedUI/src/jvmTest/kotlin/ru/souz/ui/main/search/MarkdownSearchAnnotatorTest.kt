package ru.souz.ui.main.search

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.buildAnnotatedString
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import ru.souz.ui.main.ChatMessage
import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownSearchAnnotatorTest {
    @Test
    fun `table highlights use indexed source positions across independent repeated cell rendering`() {
        val markdown = "| Name | Value |\n| --- | --- |\n| **target** | `target` |"
        val message = ChatMessage(text = markdown, isUser = false)
        val engine = ChatSearchEngine()
        val projections = engine.buildProjections(listOf(message))
        val search = engine.reindex(listOf(message), projections, ChatSearchState(query = "target"))
            .copy(currentIndex = 1)
        val part = projections.getValue(message.id).parts.single() as MarkdownTextSearchPartProjection
        val annotate = searchMarkdownAnnotator(
            projection = part,
            matchRanges = search.matchRangesForPart(message.id, 0),
            highlightColor = Color.Yellow,
            activeHighlightColor = Color.Red,
            activeRange = search.activeRangeForPart(message.id, 0),
        ).annotate!!
        val leaves = descendants(MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(markdown))
            .filter { it.type == MarkdownTokenTypes.TEXT && it.getTextInNode(markdown).toString() == "target" }

        assertEquals(2, search.matches.size)
        // Reverse and repeat the callbacks to model cells recomposing without their neighbors.
        for (index in listOf(1, 0, 1, 0)) {
            val rendered = buildAnnotatedString { annotate(markdown, leaves[index]) }
            assertEquals("target", rendered.text)
            assertEquals(if (index == 1) Color.Red else Color.Yellow, rendered.spanStyles.single().item.background)
            assertEquals(0, rendered.spanStyles.single().start)
            assertEquals(6, rendered.spanStyles.single().end)
        }
    }

    private fun descendants(node: ASTNode): List<ASTNode> = listOf(node) + node.children.flatMap(::descendants)
}
