package ru.souz.ui.sharedchat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.model.DefaultMarkdownColors
import com.mikepenz.markdown.model.DefaultMarkdownTypography
import ru.souz.ui.common.CodeBlockWithCopy
import ru.souz.ui.common.MarkdownPart
import ru.souz.ui.common.parseMarkdownContent

@Composable
fun SharedChatSurface(
    state: SharedChatUiState,
    onEvent: (SharedChatEvent) -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(state.title, style = MaterialTheme.typography.titleMedium)
                if (!state.isOnline) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = state.offlineLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(
                    onClick = { onEvent(SharedChatEvent.ClearConversation) },
                    enabled = state.canClear,
                ) {
                    Text(state.clearLabel)
                }
            }
            HorizontalDivider()
            SharedChatList(
                state = state,
                listState = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
            HorizontalDivider()
            SharedChatInput(
                state = state,
                onEvent = onEvent,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            )
        }
    }
}

@Composable
fun SharedChatList(
    state: SharedChatUiState,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.messages.isEmpty()) {
            item {
                Text(
                    text = state.emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(state.messages, key = { it.id }) { message ->
            SharedChatMessageBubble(message)
        }
        if (state.isSending) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(state.thinkingLabel, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun SharedChatInput(
    state: SharedChatUiState,
    onEvent: (SharedChatEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val canSend = state.input.isNotBlank() && state.inputEnabled && !state.isSending
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.attachments.isNotEmpty()) {
            SharedAttachmentRow(
                attachments = state.attachments,
                onRemove = { onEvent(SharedChatEvent.RemoveAttachment(it)) },
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 74.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (state.input.isEmpty()) {
                Text(
                    text = state.inputPlaceholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
            BasicTextField(
                value = state.input,
                onValueChange = { onEvent(SharedChatEvent.InputChanged(it)) },
                enabled = state.inputEnabled,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 20.sp,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 54.dp),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.canAttach) {
                IconButton(
                    onClick = { onEvent(SharedChatEvent.PickAttachments) },
                    enabled = state.inputEnabled,
                ) {
                    Icon(Icons.Rounded.AttachFile, contentDescription = null)
                }
            }
            if (state.canUseVoice) {
                IconButton(
                    onClick = {
                        if (state.isListening) {
                            onEvent(SharedChatEvent.StopListening)
                        } else {
                            onEvent(SharedChatEvent.StartListening)
                        }
                    },
                    enabled = state.inputEnabled || state.isListening,
                ) {
                    Icon(
                        imageVector = if (state.isListening) Icons.Rounded.Stop else Icons.Rounded.Mic,
                        contentDescription = null,
                    )
                }
            }
            Button(
                onClick = {
                    if (state.isSending) {
                        onEvent(SharedChatEvent.CancelProcessing)
                    } else {
                        onEvent(SharedChatEvent.SendMessage)
                    }
                },
                enabled = canSend || state.isSending,
                colors = ButtonDefaults.buttonColors(),
            ) {
                Icon(
                    imageVector = if (state.isSending) Icons.Rounded.Stop else Icons.Rounded.ArrowUpward,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = when {
                        state.isSending -> state.stopLabel
                        canSend -> state.sendLabel
                        else -> state.sendingLabel.takeIf { state.isSending } ?: state.sendLabel
                    },
                )
            }
        }
    }
}

@Composable
fun SharedMessageMarkdown(
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

@Composable
private fun SharedChatMessageBubble(message: SharedChatMessageUi) {
    val isUser = message.role == SharedChatRole.USER
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = if (isUser) 540.dp else DpUnspecifiedMax),
            shape = RoundedCornerShape(14.dp),
            color = if (isUser) {
                Color.Transparent
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            },
            border = if (isUser) BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)) else null,
        ) {
            Box(
                modifier = if (isUser) {
                    Modifier.background(
                        Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                            ),
                        ),
                    )
                } else {
                    Modifier
                },
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (message.actionLines.isNotEmpty()) {
                        SharedActionLines(message.actionLines)
                    }
                    if (message.attachments.isNotEmpty()) {
                        SharedAttachmentRow(
                            attachments = message.attachments,
                            onRemove = null,
                        )
                    }
                    SelectionContainer {
                        SharedMessageMarkdown(
                            content = message.content,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = if (isUser) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                lineHeight = 22.sp,
                            ),
                        )
                    }
                }
            }
        }
        message.timestampText?.takeIf { it.isNotBlank() }?.let { timestamp ->
            Text(
                text = timestamp,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 3.dp, start = 4.dp, end = 4.dp),
            )
        }
    }
}

@Composable
private fun SharedActionLines(actions: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        actions.forEach { action ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.6.dp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                )
                Text(
                    text = action,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                )
            }
        }
    }
}

@Composable
private fun SharedAttachmentRow(
    attachments: List<SharedChatAttachmentUi>,
    onRemove: ((String) -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEach { attachment ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f))
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 9.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Icon(
                    imageVector = when (attachment.kind) {
                        SharedChatAttachmentKind.IMAGE -> Icons.Rounded.Image
                        else -> Icons.Rounded.Description
                    },
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Column(modifier = Modifier.widthIn(max = 120.dp)) {
                    Text(
                        text = attachment.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    attachment.detail?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (onRemove != null) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onRemove(attachment.id) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
    }
}

private val DpUnspecifiedMax = 10_000.dp
