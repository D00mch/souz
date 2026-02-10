package ru.gigadesk.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

sealed class MarkdownPart {
    data class TextContent(val content: String) : MarkdownPart()
    data class CodeContent(val language: String, val code: String) : MarkdownPart()
}

fun parseMarkdownContent(input: String): List<MarkdownPart> {
    val parts = mutableListOf<MarkdownPart>()
    // Enhanced regex to be more flexible with language identifier and newlines
    // Captures:
    // 1. Language identifier (optional, can be followed by newline or space)
    // 2. Code content
    // Regex explanation:
    // ```Strart of block
    // ([\w\+\-\.\t ]*)   -> Group 1: Language identifier (optional). letters, numbers, +, -, ., space/tab.
    // (?:\r?\n)?         -> Optional newline after language. Non-capturing.
    // ([\s\S]*?)         -> Group 2: Code content. Non-greedy match of any character including newlines.
    // ```                -> End of block
    @Suppress("RegExpRedundantEscape")
    val regex = Regex("```([\\w\\+\\-\\.\\t ]*)(?:\\r?\\n)?([\\s\\S]*?)```")


    var lastIndex = 0
    regex.findAll(input).forEach { match ->
        val textBefore = input.substring(lastIndex, match.range.first)
        if (textBefore.isNotBlank()) {
            parts.add(MarkdownPart.TextContent(textBefore))
        }

        val rawInfoLine = match.groupValues[1]
        val rawCode = match.groupValues[2]

        // Heuristic: If info string contains space, split it.
        // First part is language, rest is code.
        // Unless it's a known multi-word language like "python 3" (rarely used as such in markdown, usually just python).
        // Standard markdown: first word is language.
        
        val sequence = rawInfoLine.trim().split(Regex("[\\s]+"), limit = 2)
        val lang = sequence.firstOrNull() ?: ""
        val extra = sequence.drop(1).firstOrNull()
        
        val code = if (extra != null) {
             extra + "\n" + rawCode
        } else {
             rawCode
        }

        parts.add(MarkdownPart.CodeContent(lang, code.trimEnd()))
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

@Composable
fun CodeBlockWithCopy(
    code: String,
    language: String?,
    style: TextStyle,
    onShowSnack: (String) -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val displayLang = if (!language.isNullOrBlank()) language.uppercase() else "CODE"

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
                                onShowSnack("Код скопирован")
                            }
                            .padding(7.dp), // Icon padding
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = "Copy code",
                            tint = Color.White, // Pure white icon
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            Text(
                text = code,
                style = style,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}
