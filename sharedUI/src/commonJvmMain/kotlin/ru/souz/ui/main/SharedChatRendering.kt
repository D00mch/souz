package ru.souz.ui.main

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.PresentToAll
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.model.DefaultMarkdownColors
import com.mikepenz.markdown.model.DefaultMarkdownTypography
import com.mikepenz.markdown.model.markdownAnnotator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import ru.souz.tool.files.ToolModifySelectionAction
import ru.souz.ui.common.CodeBlockWithCopy
import ru.souz.ui.common.MessageMarkdown
import ru.souz.ui.common.ToolModifyReviewBlock
import ru.souz.ui.main.search.ChatMessageSearchProjection
import ru.souz.ui.main.search.ChatSearchProjector
import ru.souz.ui.main.search.ChatSearchState
import ru.souz.ui.main.search.CodeBlockSearchPartProjection
import ru.souz.ui.main.search.MarkdownTextSearchPartProjection
import ru.souz.ui.main.search.PlainTextSearchPartProjection
import ru.souz.ui.main.search.activeRangeForPart
import ru.souz.ui.main.search.buildSearchHighlightedAnnotatedString
import ru.souz.ui.main.search.matchRangesForPart
import ru.souz.ui.main.search.rememberSearchMarkdownAnnotator
import souz.sharedui.generated.resources.Res
import souz.sharedui.generated.resources.quick_action_analytics_description
import souz.sharedui.generated.resources.quick_action_analytics_label
import souz.sharedui.generated.resources.quick_action_analytics_message
import souz.sharedui.generated.resources.quick_action_browser_description
import souz.sharedui.generated.resources.quick_action_browser_label
import souz.sharedui.generated.resources.quick_action_browser_message
import souz.sharedui.generated.resources.quick_action_calendar_description
import souz.sharedui.generated.resources.quick_action_calendar_label
import souz.sharedui.generated.resources.quick_action_calendar_message
import souz.sharedui.generated.resources.quick_action_documents_description
import souz.sharedui.generated.resources.quick_action_documents_label
import souz.sharedui.generated.resources.quick_action_documents_message
import souz.sharedui.generated.resources.quick_action_mail_description
import souz.sharedui.generated.resources.quick_action_mail_label
import souz.sharedui.generated.resources.quick_action_mail_message
import souz.sharedui.generated.resources.quick_action_presentation_description
import souz.sharedui.generated.resources.quick_action_presentation_label
import souz.sharedui.generated.resources.quick_action_presentation_message
import souz.sharedui.generated.resources.quick_action_search_description
import souz.sharedui.generated.resources.quick_action_search_label
import souz.sharedui.generated.resources.quick_action_search_message
import souz.sharedui.generated.resources.quick_action_telegram_description
import souz.sharedui.generated.resources.quick_action_telegram_label
import souz.sharedui.generated.resources.quick_action_telegram_message
import souz.sharedui.generated.resources.welcome_quick_actions_subtitle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SharedTimestampFormatter = SimpleDateFormat("HH:mm", Locale.forLanguageTag("ru-RU"))
private val SharedSearchHighlightColor = Color(0x335CA7FF)
private val SharedSearchActiveHighlightColor = Color(0x665CA7FF)
private val SharedAttachmentPreviewSize = 64.dp

@Composable
internal fun SharedChatMessageCard(
    message: ChatMessage,
    searchState: ChatSearchState,
    searchEnabled: Boolean,
    searchProjection: ChatMessageSearchProjection?,
    onOpenPath: (String) -> Unit,
    onToggleToolModifyReviewSelection: (String, Long) -> Unit,
    onResolveToolModifyReview: (String, ToolModifySelectionAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val projectedMessage = searchProjection ?: remember(message.id, message.text, message.isUser) {
        ChatSearchProjector().project(message)
    }

    if (message.isUser) {
        SharedUserMessageCard(
            message = message,
            searchState = searchState,
            searchEnabled = searchEnabled,
            searchProjection = projectedMessage,
            onOpenPath = onOpenPath,
            modifier = modifier,
        )
    } else {
        SharedAssistantMessageCard(
            message = message,
            searchState = searchState,
            searchEnabled = searchEnabled,
            searchProjection = projectedMessage,
            onOpenPath = onOpenPath,
            onToggleToolModifyReviewSelection = onToggleToolModifyReviewSelection,
            onResolveToolModifyReview = onResolveToolModifyReview,
            modifier = modifier,
        )
    }
}

@Composable
private fun SharedUserMessageCard(
    message: ChatMessage,
    searchState: ChatSearchState,
    searchEnabled: Boolean,
    searchProjection: ChatMessageSearchProjection,
    onOpenPath: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val maxBubbleWidth = maxWidth * 0.86f
        Surface(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .widthIn(max = maxBubbleWidth),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
            tonalElevation = 1.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.End,
            ) {
                if (message.attachedFiles.isNotEmpty()) {
                    SharedMessageAttachmentsPreview(
                        files = message.attachedFiles,
                        onOpenPath = onOpenPath,
                    )
                }
                if (message.text.isNotBlank()) {
                    val part = searchProjection.parts.firstOrNull() as? PlainTextSearchPartProjection
                    val ranges = if (searchEnabled && part != null) {
                        searchState.matchRangesForPart(message.id, part.partIndex)
                    } else {
                        emptyList()
                    }
                    val activeRange = if (searchEnabled && part != null) {
                        searchState.activeRangeForPart(message.id, part.partIndex)
                    } else {
                        null
                    }
                    val text = remember(message.text, ranges, activeRange) {
                        (part?.text ?: message.text).buildSearchHighlightedAnnotatedString(
                            matchRanges = ranges,
                            highlightColor = SharedSearchHighlightColor,
                            activeHighlightColor = SharedSearchActiveHighlightColor,
                            activeRange = activeRange,
                        )
                    }
                    SelectionContainer {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                        )
                    }
                }
                SharedTimestamp(message.timestamp)
            }
        }
    }
}

@Composable
private fun SharedAssistantMessageCard(
    message: ChatMessage,
    searchState: ChatSearchState,
    searchEnabled: Boolean,
    searchProjection: ChatMessageSearchProjection,
    onOpenPath: (String) -> Unit,
    onToggleToolModifyReviewSelection: (String, Long) -> Unit,
    onResolveToolModifyReview: (String, ToolModifySelectionAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember(message.id) { mutableStateOf(false) }
    val attachmentPathKeys = remember(message.attachedFiles) {
        message.attachedFiles.map { it.path.lowercase(Locale.ROOT) }.toSet()
    }
    val clickablePaths = message.finderPaths
        .filterNot { it.path.lowercase(Locale.ROOT) in attachmentPathKeys }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Assistant",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SharedTimestamp(message.timestamp)
                }
                if (message.text.isNotBlank()) {
                    Surface(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable {
                                clipboardManager.setText(AnnotatedString(message.text))
                                copied = true
                                scope.launch {
                                    delay(1200)
                                    copied = false
                                }
                            },
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        shape = CircleShape,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (copied) Icons.Rounded.CheckCircleOutline else Icons.Rounded.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            if (message.agentActions.isNotEmpty()) {
                SharedAgentActionList(
                    actions = message.agentActions,
                    inProgress = false,
                )
            }

            message.toolModifyReview?.let { review ->
                ToolModifyReviewBlock(
                    messageId = message.id,
                    review = review,
                    onToggleSelection = onToggleToolModifyReviewSelection,
                    onResolve = onResolveToolModifyReview,
                )
            }

            if (message.text.isNotBlank()) {
                SelectionContainer {
                    if (searchEnabled) {
                        SharedSearchableAssistantMarkdown(
                            message = message,
                            searchState = searchState,
                            searchProjection = searchProjection,
                        )
                    } else {
                        MessageMarkdown(
                            content = message.text,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                        )
                    }
                }
            } else if (message.agentActions.isEmpty() && message.toolModifyReview == null) {
                Text(
                    text = "...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (message.attachedFiles.isNotEmpty()) {
                SharedMessageAttachmentsPreview(
                    files = message.attachedFiles,
                    onOpenPath = onOpenPath,
                )
            }

            if (clickablePaths.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    clickablePaths.forEach { item ->
                        SharedFinderPathChip(
                            path = item.path,
                            displayName = item.displayName,
                            isDirectory = item.isDirectory,
                            onClick = { onOpenPath(item.path) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SharedSearchableAssistantMarkdown(
    message: ChatMessage,
    searchState: ChatSearchState,
    searchProjection: ChatMessageSearchProjection,
) {
    val baseStyle = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp)
    val codeStyle = MaterialTheme.typography.bodyMedium.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurface,
    )
    val typography = sharedMarkdownTypography(baseStyle, codeStyle)
    val colors = sharedMarkdownColors(baseStyle.color)
    val linkSpanStyle = SpanStyle(
        color = typography.link.color,
        fontSize = typography.link.fontSize,
        fontWeight = typography.link.fontWeight,
        fontStyle = typography.link.fontStyle,
        letterSpacing = typography.link.letterSpacing,
        textDecoration = typography.link.textDecoration,
        background = typography.link.background,
    )

    Column {
        searchProjection.parts.forEach { part ->
            val ranges = searchState.matchRangesForPart(message.id, part.partIndex)
            val activeRange = searchState.activeRangeForPart(message.id, part.partIndex)
            when (part) {
                is MarkdownTextSearchPartProjection -> {
                    val annotator = if (ranges.isEmpty()) {
                        null
                    } else {
                        rememberSearchMarkdownAnnotator(
                            matchRanges = ranges,
                            highlightColor = SharedSearchHighlightColor,
                            activeHighlightColor = SharedSearchActiveHighlightColor,
                            activeRange = activeRange,
                            linkSpanStyle = linkSpanStyle,
                        )
                    }
                    Markdown(
                        content = part.markdown,
                        colors = colors,
                        typography = typography,
                        annotator = annotator ?: markdownAnnotator(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                is CodeBlockSearchPartProjection -> {
                    val highlightedCode = remember(part.code, ranges, activeRange) {
                        part.code.buildSearchHighlightedAnnotatedString(
                            matchRanges = ranges,
                            highlightColor = SharedSearchHighlightColor,
                            activeHighlightColor = SharedSearchActiveHighlightColor,
                            activeRange = activeRange,
                        )
                    }
                    CodeBlockWithCopy(
                        code = part.code,
                        language = part.language,
                        style = codeStyle,
                        renderedCode = highlightedCode,
                    )
                }

                is PlainTextSearchPartProjection -> {
                    val text = remember(part.text, ranges, activeRange) {
                        part.text.buildSearchHighlightedAnnotatedString(
                            matchRanges = ranges,
                            highlightColor = SharedSearchHighlightColor,
                            activeHighlightColor = SharedSearchActiveHighlightColor,
                            activeRange = activeRange,
                        )
                    }
                    Text(text = text, style = baseStyle)
                }
            }
        }
    }
}

@Composable
internal fun SharedAgentActionList(
    actions: List<String>,
    inProgress: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        actions.forEach { action ->
            SharedAgentActionRow(text = action, inProgress = inProgress)
        }
    }
}

@Composable
private fun SharedAgentActionRow(
    text: String,
    inProgress: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (inProgress) 0.9f else 0.55f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (inProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 1.8.dp,
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.CheckCircleOutline,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun SharedMessageAttachmentsPreview(
    files: List<ChatAttachedFile>,
    onOpenPath: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (files.isEmpty()) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        files.forEach { file ->
            SharedMessageAttachmentTile(file = file, onOpenPath = onOpenPath)
        }
    }
}

@Composable
private fun SharedMessageAttachmentTile(
    file: ChatAttachedFile,
    onOpenPath: (String) -> Unit,
) {
    val style = sharedAttachmentStyle(file.type)
    val bitmap = remember(file.thumbnailBytes) { decodeSharedAttachmentThumbnail(file.thumbnailBytes) }

    Surface(
        modifier = Modifier
            .width(88.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable { onOpenPath(file.path) },
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(SharedAttachmentPreviewSize)
                    .clip(RoundedCornerShape(8.dp))
                    .background(style.background)
                    .border(1.dp, style.border, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (file.type == ChatAttachmentType.IMAGE && bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = file.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        imageVector = style.icon,
                        contentDescription = null,
                        tint = style.iconTint,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Text(
                text = file.displayName,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = sharedFormatAttachmentFileSize(file.sizeBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SharedFinderPathChip(
    path: String,
    displayName: String,
    isDirectory: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = if (isDirectory) Icons.Rounded.Folder else Icons.Rounded.Description,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = displayName.ifBlank { path },
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun SharedEmptyChatWelcomeContent(
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val actions = sharedEmptyChatQuickActions()
    Column(
        modifier = modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(Res.string.welcome_quick_actions_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 148.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp),
            contentPadding = PaddingValues(0.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            userScrollEnabled = false,
        ) {
            items(actions, key = { it.id }) { action ->
                val message = stringResource(action.messageRes)
                SharedQuickActionCard(
                    action = action,
                    onClick = { onSuggestionClick(message) },
                )
            }
        }
    }
}

@Composable
private fun SharedQuickActionCard(
    action: SharedQuickAction,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(94.dp)
            .selectable(
                selected = false,
                onClick = onClick,
            ),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = action.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = stringResource(action.labelRes),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(action.descriptionRes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SharedTimestamp(timestamp: Long) {
    Text(
        text = remember(timestamp) { SharedTimestampFormatter.format(Date(timestamp)) },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun sharedMarkdownColors(textColor: Color) = DefaultMarkdownColors(
    text = textColor,
    codeText = MaterialTheme.colorScheme.onSurface,
    codeBackground = MaterialTheme.colorScheme.surfaceVariant,
    inlineCodeText = MaterialTheme.colorScheme.primary,
    inlineCodeBackground = MaterialTheme.colorScheme.surfaceVariant,
    dividerColor = MaterialTheme.colorScheme.outlineVariant,
    linkText = MaterialTheme.colorScheme.primary,
)

@Composable
private fun sharedMarkdownTypography(
    baseStyle: TextStyle,
    codeStyle: TextStyle,
) = DefaultMarkdownTypography(
    h1 = MaterialTheme.typography.titleLarge.copy(color = baseStyle.color, fontWeight = FontWeight.Bold),
    h2 = MaterialTheme.typography.titleMedium.copy(color = baseStyle.color, fontWeight = FontWeight.Bold),
    h3 = MaterialTheme.typography.titleSmall.copy(color = baseStyle.color, fontWeight = FontWeight.Bold),
    h4 = MaterialTheme.typography.bodyLarge.copy(color = baseStyle.color, fontWeight = FontWeight.Bold),
    h5 = MaterialTheme.typography.bodyMedium.copy(color = baseStyle.color, fontWeight = FontWeight.Bold),
    h6 = MaterialTheme.typography.bodyMedium.copy(color = baseStyle.color, fontWeight = FontWeight.Bold),
    text = baseStyle,
    paragraph = baseStyle,
    code = codeStyle,
    inlineCode = codeStyle.copy(color = MaterialTheme.colorScheme.primary),
    quote = baseStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant, fontStyle = FontStyle.Italic),
    bullet = baseStyle.copy(fontWeight = FontWeight.Bold),
    list = baseStyle,
    ordered = baseStyle,
    link = baseStyle.copy(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline),
)

private data class SharedAttachmentStyle(
    val icon: ImageVector,
    val background: Color,
    val border: Color,
    val iconTint: Color,
)

private fun sharedAttachmentStyle(type: ChatAttachmentType): SharedAttachmentStyle = when (type) {
    ChatAttachmentType.DOCUMENT -> SharedAttachmentStyle(
        icon = Icons.Rounded.Description,
        background = Color(0x263B82F6),
        border = Color(0x403B82F6),
        iconTint = Color(0xFF3B82F6),
    )
    ChatAttachmentType.IMAGE -> SharedAttachmentStyle(
        icon = Icons.Rounded.AttachFile,
        background = Color(0x268B5CF6),
        border = Color(0x408B5CF6),
        iconTint = Color(0xFF8B5CF6),
    )
    ChatAttachmentType.PDF -> SharedAttachmentStyle(
        icon = Icons.Rounded.PictureAsPdf,
        background = Color(0x26EF4444),
        border = Color(0x40EF4444),
        iconTint = Color(0xFFEF4444),
    )
    ChatAttachmentType.SPREADSHEET -> SharedAttachmentStyle(
        icon = Icons.Rounded.TableChart,
        background = Color(0x2622C55E),
        border = Color(0x4022C55E),
        iconTint = Color(0xFF22C55E),
    )
    ChatAttachmentType.VIDEO -> SharedAttachmentStyle(
        icon = Icons.Rounded.Movie,
        background = Color(0x26F59E0B),
        border = Color(0x40F59E0B),
        iconTint = Color(0xFFF59E0B),
    )
    ChatAttachmentType.AUDIO -> SharedAttachmentStyle(
        icon = Icons.Rounded.Audiotrack,
        background = Color(0x26EC4899),
        border = Color(0x40EC4899),
        iconTint = Color(0xFFEC4899),
    )
    ChatAttachmentType.ARCHIVE -> SharedAttachmentStyle(
        icon = Icons.Rounded.Archive,
        background = Color(0x26F59E0B),
        border = Color(0x40F59E0B),
        iconTint = Color(0xFFF59E0B),
    )
    ChatAttachmentType.OTHER -> SharedAttachmentStyle(
        icon = Icons.AutoMirrored.Rounded.InsertDriveFile,
        background = Color(0x14000000),
        border = Color(0x26000000),
        iconTint = Color(0x99000000),
    )
}

private fun sharedFormatAttachmentFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        bytes >= gb -> String.format(Locale.US, "%.1f GB", bytes / gb)
        bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
        bytes >= kb -> String.format(Locale.US, "%.1f KB", bytes / kb)
        else -> "$bytes B"
    }
}

private data class SharedQuickAction(
    val id: String,
    val icon: ImageVector,
    val labelRes: StringResource,
    val descriptionRes: StringResource,
    val messageRes: StringResource,
)

private fun sharedEmptyChatQuickActions() = listOf(
    SharedQuickAction(
        id = "mail",
        icon = Icons.Rounded.Mail,
        labelRes = Res.string.quick_action_mail_label,
        descriptionRes = Res.string.quick_action_mail_description,
        messageRes = Res.string.quick_action_mail_message,
    ),
    SharedQuickAction(
        id = "calendar",
        icon = Icons.Rounded.CalendarToday,
        labelRes = Res.string.quick_action_calendar_label,
        descriptionRes = Res.string.quick_action_calendar_description,
        messageRes = Res.string.quick_action_calendar_message,
    ),
    SharedQuickAction(
        id = "telegram",
        icon = Icons.Rounded.Forum,
        labelRes = Res.string.quick_action_telegram_label,
        descriptionRes = Res.string.quick_action_telegram_description,
        messageRes = Res.string.quick_action_telegram_message,
    ),
    SharedQuickAction(
        id = "documents",
        icon = Icons.Rounded.Description,
        labelRes = Res.string.quick_action_documents_label,
        descriptionRes = Res.string.quick_action_documents_description,
        messageRes = Res.string.quick_action_documents_message,
    ),
    SharedQuickAction(
        id = "presentation",
        icon = Icons.Rounded.PresentToAll,
        labelRes = Res.string.quick_action_presentation_label,
        descriptionRes = Res.string.quick_action_presentation_description,
        messageRes = Res.string.quick_action_presentation_message,
    ),
    SharedQuickAction(
        id = "analytics",
        icon = Icons.Rounded.BarChart,
        labelRes = Res.string.quick_action_analytics_label,
        descriptionRes = Res.string.quick_action_analytics_description,
        messageRes = Res.string.quick_action_analytics_message,
    ),
    SharedQuickAction(
        id = "search",
        icon = Icons.Rounded.Search,
        labelRes = Res.string.quick_action_search_label,
        descriptionRes = Res.string.quick_action_search_description,
        messageRes = Res.string.quick_action_search_message,
    ),
    SharedQuickAction(
        id = "browser",
        icon = Icons.Rounded.Public,
        labelRes = Res.string.quick_action_browser_label,
        descriptionRes = Res.string.quick_action_browser_description,
        messageRes = Res.string.quick_action_browser_message,
    ),
)
