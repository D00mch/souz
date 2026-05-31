package ru.souz.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.model.DefaultMarkdownColors
import com.mikepenz.markdown.model.DefaultMarkdownTypography

@Composable
fun MessageMarkdown(
    content: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
    codeStyle: TextStyle = MaterialTheme.typography.bodyMedium.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        color = Color(0xFFE0E0E0),
    ),
) {
    val parts = remember(content) { parseMarkdownContent(content.ifBlank { "..." }) }
    Column(modifier = modifier) {
        parts.forEach { part ->
            when (part) {
                is MarkdownPart.CodeContent -> {
                    CodeBlockWithCopy(
                        code = part.code,
                        language = part.language,
                        style = codeStyle,
                    )
                }

                is MarkdownPart.TextContent -> {
                    Markdown(
                        content = part.content,
                        colors = DefaultMarkdownColors(
                            text = textStyle.color,
                            codeText = codeStyle.color,
                            codeBackground = Color(0x66000000),
                            linkText = MaterialTheme.colorScheme.primary,
                            inlineCodeText = codeStyle.color,
                            inlineCodeBackground = Color.White.copy(alpha = 0.08f),
                            dividerColor = MaterialTheme.colorScheme.outline,
                        ),
                        typography = DefaultMarkdownTypography(
                            h1 = textStyle.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
                            h2 = textStyle.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
                            h3 = textStyle.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                            h4 = textStyle.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                            h5 = textStyle.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                            h6 = textStyle.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                            text = textStyle,
                            code = codeStyle,
                            inlineCode = codeStyle,
                            quote = textStyle,
                            paragraph = textStyle,
                            ordered = textStyle,
                            bullet = textStyle,
                            list = textStyle,
                            link = textStyle.copy(color = MaterialTheme.colorScheme.primary),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
