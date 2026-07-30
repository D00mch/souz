package ru.souz.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.souz.tool.files.ToolModifyApplyStatus
import ru.souz.tool.files.ToolModifySelectionAction
import ru.souz.ui.ToolReviewColors
import ru.souz.ui.main.ToolModifyReviewItemUi
import ru.souz.ui.main.ToolModifyReviewUi
import ru.souz.ui.souzColors

private val ToolModifyPatchPreviewMinHeight = 220.dp
private val ToolModifyPatchPreviewMaxHeight = 620.dp
private const val ToolModifyPatchPreviewMaxLines = 350

@Composable
internal fun ToolModifyPatchPreview(
    patch: String,
    minHeight: Dp = ToolModifyPatchPreviewMinHeight,
    maxHeight: Dp = ToolModifyPatchPreviewMaxHeight,
    maxLines: Int = ToolModifyPatchPreviewMaxLines,
) {
    val colors = MaterialTheme.souzColors.toolReview
    val (lines, isTruncated) = remember(patch, maxLines, colors) {
        buildPatchPreviewLines(patch, maxLines, colors)
    }
    val verticalScroll = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(
                min = minHeight,
                max = maxHeight
            )
            .clip(RoundedCornerShape(6.dp))
            .background(colors.patchBackground)
            .border(1.dp, colors.border, RoundedCornerShape(6.dp))
            .padding(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(verticalScroll),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            lines.forEach { line ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(line.backgroundColor)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = line.text,
                        color = line.color,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        softWrap = true,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            if (isTruncated) {
                Text(
                    text = "... (preview truncated)",
                    color = colors.secondaryContent,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
internal fun ToolModifyReviewBlock(
    messageId: String,
    review: ToolModifyReviewUi,
    onToggleSelection: (String, Long) -> Unit,
    onResolve: (String, ToolModifySelectionAction) -> Unit,
) {
    val colors = MaterialTheme.souzColors.toolReview

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.container)
            .border(1.dp, colors.border, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = if (review.isResolved) "Edit review result" else "Review staged EditFile changes",
            color = colors.content,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )

        review.summary?.takeIf { it.isNotBlank() }?.let { summary ->
            Text(
                text = summary,
                color = colors.secondaryContent,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
        }

        if (!review.isResolved) {
            Text(
                text = "Apply selected applies checked changes and discards the rest. Discard selected does the opposite.",
                color = colors.secondaryContent,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }

        review.items.forEach { item ->
            ToolModifyReviewItemCard(
                messageId = messageId,
                item = item,
                reviewResolved = review.isResolved,
                onToggleSelection = onToggleSelection,
            )
        }

        if (!review.isResolved) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ReviewActionButton(
                    text = "Apply selected",
                    primary = true,
                    onClick = { onResolve(messageId, ToolModifySelectionAction.APPLY_SELECTED) },
                )
                ReviewActionButton(
                    text = "Discard selected",
                    primary = false,
                    onClick = { onResolve(messageId, ToolModifySelectionAction.DISCARD_SELECTED) },
                )
            }
        }
    }
}

private fun buildPatchPreviewLines(
    patch: String,
    maxLines: Int,
    colors: ToolReviewColors,
): Pair<List<PatchPreviewLine>, Boolean> {
    if (patch.isBlank()) {
        return listOf(
            PatchPreviewLine(
                text = AnnotatedString("(empty patch)"),
                color = colors.secondaryContent,
            )
        ) to false
    }

    val allLines = patch.lines()
    val previewLines = allLines.take(maxLines)
    val preview = mutableListOf<PatchPreviewLine>()
    var index = 0

    while (index < previewLines.size) {
        val line = previewLines[index]
        if (isPatchRemovedLine(line)) {
            val removedPreviews = mutableListOf<PatchPreviewLine>()
            while (index < previewLines.size && isPatchRemovedLine(previewLines[index])) {
                removedPreviews += buildPatchPreviewLine(previewLines[index], colors)
                index += 1
            }

            val addedPreviews = mutableListOf<PatchPreviewLine>()
            var pairedIndex = 0
            while (index < previewLines.size && isPatchAddedLine(previewLines[index])) {
                val addedLine = previewLines[index]
                if (pairedIndex < removedPreviews.size) {
                    val (removedPreview, addedPreview) = buildHighlightedPatchPreviewPair(
                        removedLine = removedPreviews[pairedIndex].text.text,
                        addedLine = addedLine,
                        colors = colors,
                    )
                    removedPreviews[pairedIndex] = removedPreview
                    addedPreviews += addedPreview
                    pairedIndex += 1
                } else {
                    addedPreviews += buildPatchPreviewLine(addedLine, colors)
                }
                index += 1
            }

            preview += removedPreviews
            preview += addedPreviews
            continue
        }

        preview += buildPatchPreviewLine(line, colors)
        index += 1
    }

    return preview to (allLines.size > maxLines)
}

@Composable
private fun ToolModifyReviewItemCard(
    messageId: String,
    item: ToolModifyReviewItemUi,
    reviewResolved: Boolean,
    onToggleSelection: (String, Long) -> Unit,
) {
    val colors = MaterialTheme.souzColors.toolReview
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val borderColor by animateColorAsState(
        targetValue = when {
            reviewResolved -> statusColorFor(item.status, colors)
            item.selected -> colors.accent.copy(alpha = 0.4f)
            isHovered -> colors.secondaryContent.copy(alpha = 0.25f)
            else -> colors.border
        },
        animationSpec = tween(durationMillis = 150)
    )
    val backgroundColor by animateColorAsState(
        targetValue = when {
            reviewResolved -> statusColorFor(item.status, colors).copy(alpha = 0.08f)
            item.selected -> colors.accent.copy(alpha = 0.08f)
            isHovered -> colors.container
            else -> colors.itemContainer
        },
        animationSpec = tween(durationMillis = 150)
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .then(
                if (!reviewResolved) {
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = { onToggleSelection(messageId, item.id) },
                        )
                } else {
                    Modifier
                }
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = item.path,
                    color = colors.content,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = statusTextFor(item, reviewResolved),
                    color = statusColorFor(item.status, colors),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            if (!reviewResolved) {
                Icon(
                    imageVector = if (item.selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (item.selected) colors.accent else colors.secondaryContent,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        item.warning?.takeIf { it.isNotBlank() }?.let { warning ->
            Text(
                text = warning,
                color = colors.negative,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }

        ToolModifyPatchPreview(
            patch = item.patchPreview,
            minHeight = 120.dp,
            maxHeight = 240.dp,
            maxLines = 120,
        )
    }
}

private data class PatchPreviewLine(
    val text: AnnotatedString,
    val color: Color,
    val backgroundColor: Color = Color.Transparent,
)

private fun buildPatchPreviewLine(
    line: String,
    colors: ToolReviewColors,
    exactChangeRange: TextRange? = null,
): PatchPreviewLine {
    val kind = patchPreviewLineKind(line)
    val safeRange = exactChangeRange
        ?.let { TextRange(it.start.coerceIn(0, line.length), it.end.coerceIn(0, line.length)) }
        ?.takeIf { it.start < it.end }

    val text = if (safeRange != null) {
        buildAnnotatedString {
            append(line)
            addStyle(
                SpanStyle(background = exactChangeHighlightFor(kind, colors)),
                start = safeRange.start,
                end = safeRange.end
            )
        }
    } else {
        AnnotatedString(line)
    }

    return PatchPreviewLine(
        text = text,
        color = patchPreviewColorFor(kind, colors),
        backgroundColor = patchPreviewBackgroundFor(kind, colors),
    )
}

private fun buildHighlightedPatchPreviewPair(
    removedLine: String,
    addedLine: String,
    colors: ToolReviewColors,
): Pair<PatchPreviewLine, PatchPreviewLine> {
    val removedContent = removedLine.drop(1)
    val addedContent = addedLine.drop(1)
    val sharedPrefixLength = sharedPrefixLength(removedContent, addedContent)
    val sharedSuffixLength = sharedSuffixLength(
        removedContent,
        addedContent,
        sharedPrefixLength
    )

    val removedChangeRange = TextRange(
        start = 1 + sharedPrefixLength,
        end = 1 + removedContent.length - sharedSuffixLength
    )
    val addedChangeRange = TextRange(
        start = 1 + sharedPrefixLength,
        end = 1 + addedContent.length - sharedSuffixLength
    )

    return buildPatchPreviewLine(removedLine, colors, removedChangeRange) to
        buildPatchPreviewLine(addedLine, colors, addedChangeRange)
}

@Composable
private fun ReviewActionButton(
    text: String,
    primary: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.souzColors.toolReview
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val backgroundColor by animateColorAsState(
        targetValue = when {
            primary && isHovered -> colors.accent.copy(alpha = 0.85f)
            primary -> colors.accent
            isHovered -> colors.container
            else -> colors.itemContainer
        },
        animationSpec = tween(durationMillis = 150)
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            primary -> colors.accent
            isHovered -> colors.secondaryContent.copy(alpha = 0.25f)
            else -> colors.border
        },
        animationSpec = tween(durationMillis = 150)
    )

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            text = text,
            color = if (primary) colors.accentContent else colors.content,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun sharedPrefixLength(
    left: String,
    right: String,
): Int {
    val limit = minOf(left.length, right.length)
    var prefixLength = 0
    while (prefixLength < limit && left[prefixLength] == right[prefixLength]) {
        prefixLength += 1
    }
    return prefixLength
}

private fun sharedSuffixLength(
    left: String,
    right: String,
    prefixLength: Int,
): Int {
    val limit = minOf(left.length, right.length) - prefixLength
    var suffixLength = 0
    while (
        suffixLength < limit &&
        left[left.length - 1 - suffixLength] == right[right.length - 1 - suffixLength]
    ) {
        suffixLength += 1
    }
    return suffixLength
}

private fun isPatchAddedLine(line: String): Boolean = line.startsWith("+") && !line.startsWith("+++")

private fun isPatchRemovedLine(line: String): Boolean = line.startsWith("-") && !line.startsWith("---")

private fun statusTextFor(item: ToolModifyReviewItemUi, reviewResolved: Boolean): String =
    if (!reviewResolved) {
        if (item.selected) "Selected" else "Not selected"
    } else {
        when (item.status) {
            ToolModifyApplyStatus.APPLIED -> "Applied"
            ToolModifyApplyStatus.DISCARDED -> "Discarded"
            ToolModifyApplyStatus.SKIPPED_CONFLICT -> "Skipped: dependency conflict"
            ToolModifyApplyStatus.SKIPPED_EXTERNAL_CONFLICT -> "Skipped: file changed on disk"
            null -> "Pending"
        }
    }

private fun statusColorFor(
    status: ToolModifyApplyStatus?,
    colors: ToolReviewColors,
): Color = when (status) {
    ToolModifyApplyStatus.APPLIED -> colors.positive
    ToolModifyApplyStatus.DISCARDED -> colors.secondaryContent
    ToolModifyApplyStatus.SKIPPED_CONFLICT,
    ToolModifyApplyStatus.SKIPPED_EXTERNAL_CONFLICT -> colors.warning
    null -> colors.accent
}

private fun patchPreviewLineKind(line: String): PatchPreviewLineKind = when {
    line.startsWith("---") -> PatchPreviewLineKind.FileHeader
    line.startsWith("+++") -> PatchPreviewLineKind.FileHeader
    line.startsWith("@@") -> PatchPreviewLineKind.HunkHeader
    isPatchAddedLine(line) -> PatchPreviewLineKind.Added
    isPatchRemovedLine(line) -> PatchPreviewLineKind.Removed
    line.startsWith("diff ") ||
        line.startsWith("index ") ||
        line.startsWith("new file mode ") ||
        line.startsWith("deleted file mode ") ||
        line.startsWith("rename from ") ||
        line.startsWith("rename to ") ||
        line.startsWith("similarity index ") ||
        line.startsWith("Binary files ") -> PatchPreviewLineKind.Meta
    else -> PatchPreviewLineKind.Context
}

private fun patchPreviewColorFor(
    kind: PatchPreviewLineKind,
    colors: ToolReviewColors,
): Color = when (kind) {
    PatchPreviewLineKind.FileHeader -> colors.info
    PatchPreviewLineKind.HunkHeader -> colors.warning
    PatchPreviewLineKind.Added -> colors.positive
    PatchPreviewLineKind.Removed -> colors.negative
    PatchPreviewLineKind.Meta -> colors.secondaryContent
    PatchPreviewLineKind.Context -> colors.content
}

private fun patchPreviewBackgroundFor(
    kind: PatchPreviewLineKind,
    colors: ToolReviewColors,
): Color = when (kind) {
    PatchPreviewLineKind.Added -> colors.positive.copy(alpha = 0.08f)
    PatchPreviewLineKind.Removed -> colors.negative.copy(alpha = 0.08f)
    PatchPreviewLineKind.HunkHeader -> colors.warning.copy(alpha = 0.06f)
    else -> Color.Transparent
}

private fun exactChangeHighlightFor(
    kind: PatchPreviewLineKind,
    colors: ToolReviewColors,
): Color = when (kind) {
    PatchPreviewLineKind.Added -> colors.positive.copy(alpha = 0.2f)
    PatchPreviewLineKind.Removed -> colors.negative.copy(alpha = 0.2f)
    else -> Color.Transparent
}

private enum class PatchPreviewLineKind {
    FileHeader,
    HunkHeader,
    Added,
    Removed,
    Meta,
    Context,
}
