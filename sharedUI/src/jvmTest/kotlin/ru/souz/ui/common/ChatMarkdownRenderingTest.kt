package ru.souz.ui.common

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.LocalReferenceLinkHandler
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.model.DefaultMarkdownColors
import com.mikepenz.markdown.model.DefaultMarkdownTypography
import com.mikepenz.markdown.utils.MARKDOWN_TAG_URL
import com.mikepenz.markdown.utils.buildMarkdownAnnotatedString
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import ru.souz.ui.main.ChatMessage
import ru.souz.ui.main.search.ChatSearchProjector
import ru.souz.ui.main.search.MarkdownTextSearchPartProjection
import ru.souz.ui.main.search.SearchTextRange
import ru.souz.ui.main.search.searchMarkdownAnnotator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class ChatMarkdownRenderingTest {
    private val style = TextStyle(fontSize = 14.sp)
    private val typography = DefaultMarkdownTypography(
        h1 = style, h2 = style, h3 = style, h4 = style, h5 = style, h6 = style,
        text = style, code = style, inlineCode = style, quote = style, paragraph = style,
        ordered = style, bullet = style, list = style, link = style,
    )
    private val colors = DefaultMarkdownColors(
        text = Color.Black, codeText = Color.Black, inlineCodeText = Color.Black,
        linkText = Color.Blue, codeBackground = Color.LightGray,
        inlineCodeBackground = Color.LightGray, dividerColor = Color.Gray,
    )

    @Test
    fun `oversized cells lay out in both message roles at desktop densities`() {
        val markdown = "| Header |\n| --- |\n| ${"W".repeat(40_000)} |"
        for (density in listOf(1f, 2f)) {
            for (isUser in listOf(true, false)) {
                val part = ChatSearchProjector().project(ChatMessage(text = markdown, isUser = isUser))
                    .parts.single() as MarkdownTextSearchPartProjection
                val scene = ImageComposeScene(width = 640, height = 480, density = Density(density)) {
                    Markdown(
                        content = part.markdown,
                        colors = colors,
                        typography = typography,
                        components = chatMarkdownComponents(),
                    )
                }
                try {
                    val image = scene.render()
                    try {
                        assertEquals(640, image.width)
                    } finally {
                        image.close()
                    }
                } finally {
                    scene.close()
                }
            }
        }
    }

    @Test
    fun `user table reference links resolve from the full message and keep search offsets`() {
        val table = "| Links |\n| --- |\n| [Docs][ref] and [Other][other] |"
        val before = "**literal**\n\n[ref]: https://example.com/docs \"Docs title\"\n\n"
        val after = "\n\n*literal too*\n\n[other]: https://example.com/other"
        val parts = ChatSearchProjector().project(ChatMessage(text = before + table + after, isUser = true)).parts
        val part = parts.filterIsInstance<MarkdownTextSearchPartProjection>().single()
        assertEquals(before, parts.first().searchableText)
        assertEquals(after, parts.last().searchableText)
        assertEquals("Links\nDocs and Other", part.searchableText)
        var rendered = AnnotatedString("")
        var destinations = emptyList<String>()
        val scene = ImageComposeScene(width = 640, height = 480) {
            Markdown(
                content = part.markdown,
                colors = colors,
                typography = typography,
                annotator = searchMarkdownAnnotator(part, listOf(SearchTextRange(6, 10)), Color.Yellow),
                components = markdownComponents(custom = { type, model ->
                    if (type == GFMElementTypes.TABLE) {
                        val cell = model.node.chatMarkdownTable(model.content).rows[1][0]!!
                        rendered = buildAnnotatedString {
                            buildMarkdownAnnotatedString(model.content, cell.tableCellChildren())
                        }
                        val references = LocalReferenceLinkHandler.current
                        destinations = rendered.getStringAnnotations(MARKDOWN_TAG_URL, 0, rendered.length)
                            .map { references.find(it.item) }
                    }
                }),
            )
        }
        try {
            scene.render().close()
            assertEquals("Docs and Other", rendered.text)
            assertEquals(
                listOf("https://example.com/docs", "https://example.com/other"),
                destinations,
            )
            assertTrue(rendered.spanStyles.any { it.start == 0 && it.end == 4 && it.item.background == Color.Yellow })
        } finally {
            scene.close()
        }
    }
}
