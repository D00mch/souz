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
import ru.souz.ui.main.ToolModifyReviewItemUi
import ru.souz.ui.main.ToolModifyReviewUi

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
    val (lines, isTruncated) = remember(patch, maxLines) {
        buildPatchPreviewLines(patch, maxLines)
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
            .background(Color(0x33000000))
            .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(6.dp))
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
                    color = Color(0x99FFFFFF),
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x10FFFFFF))
            .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = if (review.isResolved) "Edit review result" else "Review staged EditFile changes",
            color = Color(0xE6FFFFFF),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )

        review.summary?.takeIf { it.isNotBlank() }?.let { summary ->
            Text(
                text = summary,
                color = Color(0x99FFFFFF),
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
        }

        if (!review.isResolved) {
            Text(
                text = "Apply selected applies checked changes and discards the rest. Discard selected does the opposite.",
                color = Color(0x80FFFFFF),
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
): Pair<List<PatchPreviewLine>, Boolean> {
    if (patch.isBlank()) {
        return listOf(
            PatchPreviewLine(
                text = AnnotatedString("(empty patch)"),
                color = Color(0x99FFFFFF),
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
                removedPreviews += buildPatchPreviewLine(previewLines[index])
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
                    )
                    removedPreviews[pairedIndex] = removedPreview
                    addedPreviews += addedPreview
                    pairedIndex += 1
                } else {
                    addedPreviews += buildPatchPreviewLine(addedLine)
                }
                index += 1
            }

            preview += removedPreviews
            preview += addedPreviews
            continue
        }

        preview += buildPatchPreviewLine(line)
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
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val borderColor by animateColorAsState(
        targetValue = when {
            reviewResolved -> statusColorFor(item.status)
            item.selected -> Color(0x66F59E0B)
            isHovered -> Color(0x33FFFFFF)
            else -> Color(0x1AFFFFFF)
        },
        animationSpec = tween(durationMillis = 150)
    )
    val backgroundColor by animateColorAsState(
        targetValue = when {
            reviewResolved -> statusColorFor(item.status).copy(alpha = 0.08f)
            item.selected -> Color(0x14F59E0B)
            isHovered -> Color(0x10FFFFFF)
            else -> Color(0x08FFFFFF)
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
                    color = Color(0xF2FFFFFF),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = statusTextFor(item, reviewResolved),
                    color = statusColorFor(item.status),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            if (!reviewResolved) {
                Icon(
                    imageVector = if (item.selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (item.selected) Color(0xFFF59E0B) else Color(0x66FFFFFF),
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        item.warning?.takeIf { it.isNotBlank() }?.let { warning ->
            Text(
                text = warning,
                color = Color(0xFFFFB4AB),
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
                SpanStyle(background = exactChangeHighlightFor(kind)),
                start = safeRange.start,
                end = safeRange.end
            )
        }
    } else {
        AnnotatedString(line)
    }

    return PatchPreviewLine(
        text = text,
        color = patchPreviewColorFor(kind),
        backgroundColor = patchPreviewBackgroundFor(kind),
    )
}

private fun buildHighlightedPatchPreviewPair(
    removedLine: String,
    addedLine: String,
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

    return buildPatchPreviewLine(removedLine, removedChangeRange) to
        buildPatchPreviewLine(addedLine, addedChangeRange)
}

@Composable
private fun ReviewActionButton(
    text: String,
    primary: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val backgroundColor by animateColorAsState(
        targetValue = when {
            primary && isHovered -> Color(0x33FFFFFF)
            primary -> Color(0x1FFFFFFF)
            isHovered -> Color(0x18FFFFFF)
            else -> Color(0x10FFFFFF)
        },
        animationSpec = tween(durationMillis = 150)
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            primary && isHovered -> Color(0x44FFFFFF)
            primary -> Color(0x33FFFFFF)
            isHovered -> Color(0x28FFFFFF)
            else -> Color(0x1AFFFFFF)
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
            color = Color.White,
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

private fun statusColorFor(status: ToolModifyApplyStatus?): Color = when (status) {
    ToolModifyApplyStatus.APPLIED -> Color(0xFF4ADE80)
    ToolModifyApplyStatus.DISCARDED -> Color(0xFFB0BEC5)
    ToolModifyApplyStatus.SKIPPED_CONFLICT,
    ToolModifyApplyStatus.SKIPPED_EXTERNAL_CONFLICT -> Color(0xFFFFB74D)
    null -> Color(0xFFF59E0B)
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

private fun patchPreviewColorFor(kind: PatchPreviewLineKind): Color = when (kind) {
    PatchPreviewLineKind.FileHeader -> Color(0xFF90CAF9)
    PatchPreviewLineKind.HunkHeader -> Color(0xFFFFCC80)
    PatchPreviewLineKind.Added -> Color(0xFFB9F6CA)
    PatchPreviewLineKind.Removed -> Color(0xFFFF8A80)
    PatchPreviewLineKind.Meta -> Color(0xFFB0BEC5)
    PatchPreviewLineKind.Context -> Color(0xCCFFFFFF)
}

private fun patchPreviewBackgroundFor(kind: PatchPreviewLineKind): Color = when (kind) {
    PatchPreviewLineKind.Added -> Color(0x1A1B5E20)
    PatchPreviewLineKind.Removed -> Color(0x1A7F1D1D)
    PatchPreviewLineKind.HunkHeader -> Color(0x10FFB74D)
    else -> Color.Transparent
}

private fun exactChangeHighlightFor(kind: PatchPreviewLineKind): Color = when (kind) {
    PatchPreviewLineKind.Added -> Color(0x334ADE80)
    PatchPreviewLineKind.Removed -> Color(0x33FF6B6B)
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
