package ru.souz.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.markdownAnnotator
import kotlinx.coroutines.delay
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser

sealed class MarkdownPart {
    data class TextContent(val content: String) : MarkdownPart()
    data class CodeContent(val language: String, val code: String) : MarkdownPart()
}

private data class FenceInfo(
    val language: String,
    val inlineCodePrefix: String?
)

private val KnownFenceLanguages = listOf(
    "typescript",
    "javascript",
    "markdown",
    "kotlin",
    "python",
    "dockerfile",
    "yaml",
    "json",
    "bash",
    "shell",
    "sql",
    "java",
    "swift",
    "scala",
    "groovy",
    "csharp",
    "cpp",
    "c",
    "rust",
    "go",
    "php",
    "ruby",
    "perl",
    "lua",
    "xml",
    "html",
    "css",
    "toml",
    "ini",
    "sh",
)

private fun parseFenceInfo(rawInfoLine: String): FenceInfo {
    val info = rawInfoLine.trim()
    if (info.isEmpty()) {
        return FenceInfo(language = "", inlineCodePrefix = null)
    }

    val firstToken = info.takeWhile { !it.isWhitespace() }
    val rest = info.removePrefix(firstToken).trimStart()
    val normalizedToken = firstToken.lowercase()

    if (KnownFenceLanguages.contains(normalizedToken)) {
        return FenceInfo(
            language = normalizedToken,
            inlineCodePrefix = rest.ifBlank { null }
        )
    }

    val gluedLanguage = KnownFenceLanguages
        .firstOrNull { lang -> normalizedToken.startsWith(lang) && normalizedToken.length > lang.length }

    if (gluedLanguage != null) {
        val gluedRemainder = firstToken.substring(gluedLanguage.length)
        val inline = listOf(gluedRemainder, rest)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()
        return FenceInfo(
            language = gluedLanguage,
            inlineCodePrefix = inline.ifBlank { null }
        )
    }

    val language = Regex("^[A-Za-z0-9_+.-]+")
        .find(firstToken)
        ?.value
        ?.lowercase()
        .orEmpty()
    return FenceInfo(
        language = language,
        inlineCodePrefix = rest.ifBlank { null }
    )
}

fun parseMarkdownContent(input: String): List<MarkdownPart> {
    val parts = mutableListOf<MarkdownPart>()
    // Ищем только корректные fenced code blocks:
    // - fence открывается с начала строки
    // - info string и код разделены переводом строки
    // - fence закрывается на отдельной строке
    val regex = Regex("(?m)^[\\t ]{0,3}```([^\\r\\n`]*)[\\t ]*\\r?\\n([\\s\\S]*?)\\r?\\n[\\t ]{0,3}```[\\t ]*(?=\\r?\\n|$)")
    var lastIndex = 0
    regex.findAll(input).forEach { match ->
        val textBefore = input.substring(lastIndex, match.range.first)
        if (textBefore.isNotBlank()) {
            parts.add(MarkdownPart.TextContent(textBefore))
        }

        val fenceInfo = parseFenceInfo(match.groupValues[1])
        val codeBody = match.groupValues[2].trimEnd('\r', '\n')
        val code = if (!fenceInfo.inlineCodePrefix.isNullOrBlank()) {
            "${fenceInfo.inlineCodePrefix}\n$codeBody".trimEnd('\r', '\n')
        } else {
            codeBody
        }

        parts.add(MarkdownPart.CodeContent(fenceInfo.language, code))
        lastIndex = match.range.last + 1
    }

    if (lastIndex < input.length) {
        val textAfter = input.substring(lastIndex)
        if (textAfter.isNotBlank()) {
            parts.add(MarkdownPart.TextContent(textAfter))
        }
    }

    return parts
}

fun buildSearchHighlightedAnnotatedString(
    text: String,
    query: String,
    highlightColor: Color,
    activeHighlightColor: Color = highlightColor,
    activeMatchIndex: Int? = null,
): AnnotatedString = buildAnnotatedString {
    appendHighlightedMatches(
        text = text,
        query = query,
        highlightColor = highlightColor,
        activeHighlightColor = activeHighlightColor,
        activeMatchIndex = activeMatchIndex,
    )
}

fun String.findSearchMatchStartIndexes(
    query: String,
): List<Int> {
    val text = this
    if (query.isBlank() || text.isEmpty()) return emptyList()

    val matches = mutableListOf<Int>()
    var currentIndex = 0
    while (currentIndex < text.length) {
        val matchIndex = text.indexOf(query, startIndex = currentIndex, ignoreCase = true)
        if (matchIndex < 0) break
        matches += matchIndex
        currentIndex = matchIndex + query.length
    }
    return matches
}

fun buildSearchableMessageText(
    text: String,
    isUserMessage: Boolean,
): String {
    if (isUserMessage || text.isBlank()) return text

    return buildString {
        parseMarkdownContent(text).forEachIndexed { index, part ->
            if (index > 0 && isNotEmpty() && last() != '\n') {
                append('\n')
            }
            when (part) {
                is MarkdownPart.TextContent -> appendPlainMarkdownText(part.content)
                is MarkdownPart.CodeContent -> append(part.code)
            }
        }
    }
}

@Composable
fun rememberSearchMarkdownAnnotator(
    query: String,
    highlightColor: Color,
    activeHighlightColor: Color = highlightColor,
    activeMatchIndex: Int? = null,
): MarkdownAnnotator {
    var consumedMatchCount = 0

    return markdownAnnotator { content: String, child: ASTNode ->
        if (query.isBlank() || child.type != MarkdownTokenTypes.TEXT) {
            return@markdownAnnotator false
        }
        val childText = child.getTextInNode(content).toString()
        val childMatchCount = childText.findSearchMatchStartIndexes(query).size
        val childActiveMatchIndex = activeMatchIndex
            ?.minus(consumedMatchCount)
            ?.takeIf { it in 0 until childMatchCount }

        appendHighlightedMatches(
            text = childText,
            query = query,
            highlightColor = highlightColor,
            activeHighlightColor = activeHighlightColor,
            activeMatchIndex = childActiveMatchIndex,
        )
        consumedMatchCount += childMatchCount
        true
    }
}

private fun AnnotatedString.Builder.appendHighlightedMatches(
    text: String,
    query: String,
    highlightColor: Color,
    activeHighlightColor: Color,
    activeMatchIndex: Int?,
) {
    if (query.isBlank() || text.isEmpty()) {
        append(text)
        return
    }

    var currentIndex = 0
    text.findSearchMatchStartIndexes(query).forEachIndexed { matchOrdinal, matchIndex ->
        if (matchIndex > currentIndex) {
            append(text.substring(currentIndex, matchIndex))
        }
        val color = if (matchOrdinal == activeMatchIndex) activeHighlightColor else highlightColor
        pushStyle(SpanStyle(background = color))
        append(text.substring(matchIndex, matchIndex + query.length))
        pop()
        currentIndex = matchIndex + query.length
    }
    if (currentIndex < text.length) {
        append(text.substring(currentIndex))
    }
}

private fun StringBuilder.appendPlainMarkdownText(markdown: String) {
    if (markdown.isBlank()) return

    val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(markdown)
    appendVisibleMarkdownNodes(markdown, tree.children)
}

private fun StringBuilder.appendVisibleMarkdownNodes(
    content: String,
    children: List<ASTNode>,
) {
    children.forEach { child ->
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
            GFMElementTypes.STRIKETHROUGH -> appendVisibleMarkdownNodes(content, child.children)

            MarkdownElementTypes.CODE_SPAN -> appendVisibleMarkdownNodes(
                content = content,
                children = child.children.filterNot { it.type == MarkdownTokenTypes.BACKTICK },
            )

            MarkdownElementTypes.INLINE_LINK,
            MarkdownElementTypes.SHORT_REFERENCE_LINK,
            MarkdownElementTypes.FULL_REFERENCE_LINK -> {
                val linkText = child.findChildOfType(MarkdownElementTypes.LINK_TEXT)
                if (linkText != null) {
                    appendVisibleMarkdownNodes(content, linkText.children)
                }
            }

            MarkdownElementTypes.AUTOLINK -> append(child.getTextInNode(content).toString().trim('<', '>'))
            MarkdownElementTypes.IMAGE,
            MarkdownElementTypes.LINK_DEFINITION -> Unit

            MarkdownTokenTypes.TEXT -> append(child.getTextInNode(content))
            MarkdownTokenTypes.WHITE_SPACE -> append(' ')
            MarkdownTokenTypes.EOL,
            MarkdownTokenTypes.HARD_LINE_BREAK -> append('\n')
            MarkdownTokenTypes.SINGLE_QUOTE -> append('\'')
            MarkdownTokenTypes.DOUBLE_QUOTE -> append('\"')
            MarkdownTokenTypes.LPAREN -> append('(')
            MarkdownTokenTypes.RPAREN -> append(')')
            MarkdownTokenTypes.LBRACKET -> append('[')
            MarkdownTokenTypes.RBRACKET -> append(']')
            MarkdownTokenTypes.LT -> append('<')
            MarkdownTokenTypes.GT -> append('>')
            MarkdownTokenTypes.COLON -> append(':')
            MarkdownTokenTypes.EXCLAMATION_MARK -> append('!')
            GFMTokenTypes.GFM_AUTOLINK -> append(child.getTextInNode(content))
            else -> appendVisibleMarkdownNodes(content, child.children)
        }
    }
}

@Composable
fun CodeBlockWithCopy(
    code: String,
    language: String?,
    style: TextStyle,
    searchQuery: String = "",
    highlightColor: Color = Color.Transparent,
    activeHighlightColor: Color = highlightColor,
    activeMatchIndex: Int? = null,
) {
    val clipboardManager = LocalClipboardManager.current
    val displayLang = if (!language.isNullOrBlank()) language.uppercase() else "CODE"
    var copied by remember(code) { mutableStateOf(false) }
    val highlightedCode = remember(
        code,
        searchQuery,
        highlightColor,
        activeHighlightColor,
        activeMatchIndex,
    ) {
        buildSearchHighlightedAnnotatedString(
            text = code,
            query = searchQuery,
            highlightColor = highlightColor,
            activeHighlightColor = activeHighlightColor,
            activeMatchIndex = activeMatchIndex,
        )
    }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1200)
            copied = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.4f))
            .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(8.dp))
    ) {
        Column {
            DisableSelection {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF2A2A2A)) // Darker, explicit header background
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = displayLang,
                        style = TextStyle(
                            color = Color.White.copy(0.7f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    )

                    Box(
                        modifier = Modifier
                            .size(32.dp) // Slightly larger touch target
                            .clip(CircleShape)
                            .background(Color.White.copy(0.15f)) // More visible button bg
                            .clickable {
                                clipboardManager.setText(AnnotatedString(code))
                                copied = true
                            }
                            .padding(7.dp), // Icon padding
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                            contentDescription = if (copied) "Copied" else "Copy code",
                            tint = Color.White, // Pure white icon
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            Text(
                text = highlightedCode,
                style = style,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}
