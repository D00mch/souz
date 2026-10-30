package ru.souz.ui.common

import androidx.compose.ui.text.style.TextAlign
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatMarkdownTableTest {
    @Test
    fun `GFM column alignment and body cell counts follow the header`() {
        val content = """
            Left | Center | Right | Default
            :--- | :---: | ---: | ---
            a\|b | **bold** | *em* | `code` | ignored
            short | [label](https://example.com)
        """.trimIndent()
        val node = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(content)
            .children.single { it.type == GFMElementTypes.TABLE }
        val table = node.chatMarkdownTable(content)

        assertEquals(listOf(TextAlign.Left, TextAlign.Center, TextAlign.Right, TextAlign.Left), table.alignments)
        assertEquals(listOf(4, 4, 4), table.rows.map { it.size })
        assertEquals("a\\|b", table.rows[1][0]!!.tableCellChildren().single().getTextInNode(content).toString())
        assertNull(table.rows[2][2])
        assertNull(table.rows[2][3])
    }
}
