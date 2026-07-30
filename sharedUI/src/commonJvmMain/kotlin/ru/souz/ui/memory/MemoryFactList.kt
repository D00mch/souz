package ru.souz.ui.memory

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.stringResource
import ru.souz.memory.MemoryFact
import ru.souz.memory.MemoryFactKind
import ru.souz.ui.MemoryKindColors
import ru.souz.ui.memoryColors
import ru.souz.ui.souzColors
import souz.sharedui.generated.resources.Res
import souz.sharedui.generated.resources.memory_action_delete
import souz.sharedui.generated.resources.memory_empty
import souz.sharedui.generated.resources.memory_error_inline_dismiss
import souz.sharedui.generated.resources.memory_filter_kind_all
import souz.sharedui.generated.resources.memory_filter_query
import souz.sharedui.generated.resources.memory_loading

@Composable
internal fun MemoryFactKind.kindStyle(): MemoryKindColors {
    val colors = MaterialTheme.souzColors.memory
    return when (this) {
        MemoryFactKind.SEMANTIC -> colors.semantic
        MemoryFactKind.PREFERENCE -> colors.preference
        MemoryFactKind.PROCEDURE -> colors.procedure
        MemoryFactKind.PROJECT_RULE -> colors.projectRule
        MemoryFactKind.EPISODE_NOTE -> colors.episodeNote
        MemoryFactKind.PROJECT_DECISION -> colors.projectDecision
    }
}

@Composable
internal fun MemoryFilters(
    filters: MemoryFiltersUi,
    visibleCount: Int,
    onFiltersChange: (MemoryFiltersUi) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MemorySearchField(
            query = filters.query,
            onQueryChange = { onFiltersChange(filters.copy(query = it)) },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MemoryKindDropdown(
                selectedKind = filters.kind,
                modifier = Modifier.weight(1f),
                onSelect = { onFiltersChange(filters.copy(kind = it)) },
            )
            MemoryStatusSegments(
                selected = filters.status,
                onSelected = { onFiltersChange(filters.copy(status = it)) },
            )
            Text(
                text = visibleCount.toString(),
                color = MaterialTheme.memoryColors.textPrimary.copy(alpha = 0.2f),
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun MemorySearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth(),
        singleLine = true,
        textStyle = TextStyle(
            color = MaterialTheme.memoryColors.textPrimary.copy(alpha = 0.84f),
            fontSize = 12.5.sp,
        ),
        cursorBrush = SolidColor(MaterialTheme.memoryColors.accent),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(MaterialTheme.memoryColors.textPrimary.copy(alpha = 0.05f))
                    .border(1.dp, MaterialTheme.memoryColors.textPrimary.copy(alpha = 0.1f), RoundedCornerShape(11.dp))
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = MaterialTheme.memoryColors.textPrimary.copy(alpha = 0.32f),
                    modifier = Modifier.size(16.dp),
                )
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (query.isBlank()) {
                        Text(
                            text = stringResource(Res.string.memory_filter_query),
                            color = MaterialTheme.memoryColors.textPrimary.copy(alpha = 0.28f),
                            fontSize = 12.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
                if (query.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onQueryChange("") },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = null,
                            tint = MaterialTheme.memoryColors.textPrimary.copy(alpha = 0.35f),
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun MemoryKindDropdown(
    selectedKind: MemoryFactKind?,
    modifier: Modifier = Modifier,
    onSelect: (MemoryFactKind?) -> Unit,
) {
    val allLabel = stringResource(Res.string.memory_filter_kind_all)
    MemoryMenuField(
        selectedText = selectedKind?.label() ?: allLabel,
        selectedColor = selectedKind?.kindStyle()?.content ?: MaterialTheme.memoryColors.textPrimary.copy(alpha = 0.5f),
        modifier = modifier,
        options = listOf(
            MemoryMenuOption(
                title = allLabel,
                color = MaterialTheme.memoryColors.textPrimary.copy(alpha = 0.5f),
                action = { onSelect(null) },
            )
        ) + MemoryFactKind.entries.map { kind ->
            MemoryMenuOption(kind.label(), kind.kindStyle().content) { onSelect(kind) }
        },
    )
}

@Composable
private fun MemoryStatusSegments(
    selected: MemoryStatusFilter,
    onSelected: (MemoryStatusFilter) -> Unit,
) {
    Row(
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.memoryColors.textPrimary.copy(alpha = 0.05f))
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MemoryStatusFilter.entries.forEach { status ->
            val active = selected == status
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (active) MaterialTheme.memoryColors.textPrimary.copy(alpha = 0.1f) else Color.Transparent)
                    .clickable { onSelected(status) }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = status.label(),
                    color = MaterialTheme.memoryColors.textPrimary.copy(alpha = if (active) 0.8f else 0.3f),
                    fontSize = 10.5.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
internal fun MemoryFactsContent(
    state: MemoryUiState,
    onAction: (MemoryAction) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (state.error != null && state.facts.isNotEmpty()) {
            MemoryInlineError(
                message = state.error,
                onDismiss = { onAction(MemoryAction.ClearError) },
            )
            Spacer(Modifier.height(8.dp))
        }

        when {
            state.isLoading && state.facts.isEmpty() -> MemoryCenteredText(stringResource(Res.string.memory_loading))
            state.error != null && state.facts.isEmpty() -> MemoryCenteredText(state.error)
            state.facts.isEmpty() -> MemoryCenteredText(stringResource(Res.string.memory_empty))
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(state.facts, key = { _, fact -> fact.id }) { _, fact ->
                        MemoryFactCard(
                            fact = fact,
                            selected = state.detailsFactId == fact.id,
                            onAction = onAction,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryFactCard(
    fact: MemoryFact,
    selected: Boolean,
    onAction: (MemoryAction) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val shape = RoundedCornerShape(12.dp)
    val background = when {
        selected -> MaterialTheme.memoryColors.selectedCard
        hovered -> MaterialTheme.memoryColors.cardHover
        else -> MaterialTheme.memoryColors.card
    }
    val border = if (selected) MaterialTheme.memoryColors.selectedBorder else MaterialTheme.memoryColors.cardBorder

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background)
            .border(1.dp, border, shape)
            .hoverable(interactionSource)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interactionSource, indication = null) {
                onAction(MemoryAction.OpenDetails(fact.id))
            }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (fact.pinned) {
                    Icon(
                        imageVector = Icons.Rounded.PushPin,
                        contentDescription = null,
                        tint = MaterialTheme.memoryColors.accent,
                        modifier = Modifier.size(12.dp),
                    )
                }
                Text(
                    text = fact.title,
                    color = MaterialTheme.memoryColors.textPrimary.copy(alpha = 0.9f),
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = fact.updatedAt.shortMemoryLabel(),
                    color = MaterialTheme.memoryColors.textPrimary.copy(alpha = 0.3f),
                    fontSize = 11.sp,
                )
                if (hovered) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onAction(MemoryAction.AskDelete(fact.id)) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = stringResource(Res.string.memory_action_delete),
                            tint = MaterialTheme.memoryColors.textPrimary.copy(alpha = 0.45f),
                            modifier = Modifier.size(13.dp),
                        )
                    }
                } else {
                    Spacer(Modifier.size(24.dp))
                }
            }
        }

        Text(
            text = fact.body.preview(),
            color = MaterialTheme.memoryColors.textPrimary.copy(alpha = 0.45f),
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KindBadge(fact.kind)
                Text(
                    text = fact.scope.memoryLabel(),
                    color = MaterialTheme.memoryColors.textPrimary.copy(alpha = 0.3f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 140.dp),
                )
            }
            ConfidenceBar(confidence = fact.confidence, width = 56.dp, height = 4.dp)
        }
    }
}

@Composable
internal fun KindBadge(kind: MemoryFactKind) {
    val style = kind.kindStyle()
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(style.container)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(style.content, RoundedCornerShape(999.dp)),
        )
        Text(
            text = kind.label(),
            color = style.content,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
internal fun ConfidenceBar(
    confidence: Float,
    modifier: Modifier = Modifier,
    width: androidx.compose.ui.unit.Dp = 64.dp,
    height: androidx.compose.ui.unit.Dp = 4.dp,
    showLabel: Boolean = true,
) {
    val normalized = confidence.coerceIn(0f, 1f)
    val color = confidenceColor(normalized)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(width)
                .height(height)
                .clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.memoryColors.textPrimary.copy(alpha = 0.1f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(normalized)
                    .clip(RoundedCornerShape(999.dp))
                    .background(color),
            )
        }
        if (showLabel) {
            Text(
                text = confidence.confidenceLabel(),
                color = color,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
internal fun confidenceColor(confidence: Float): Color {
    val colors = MaterialTheme.souzColors.memory
    return when {
        confidence >= 0.85f -> MaterialTheme.memoryColors.accent
        confidence >= 0.65f -> colors.warning.content
        else -> colors.danger.content
    }
}

@Composable
internal fun MemoryInlineError(
    message: String,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.souzColors.memory
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.danger.container)
            .border(1.dp, colors.danger.content.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            modifier = Modifier.weight(1f),
            color = colors.danger.content,
            fontSize = 12.sp,
        )
        Text(
            text = stringResource(Res.string.memory_error_inline_dismiss),
            color = colors.danger.content,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.clickable(onClick = onDismiss),
        )
    }
}

@Composable
internal fun MemoryCenteredText(
    text: String,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = MaterialTheme.memoryColors.textPrimary.copy(alpha = 0.45f),
            fontSize = 13.sp,
        )
    }
}

@Composable
internal fun memoryTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = MaterialTheme.memoryColors.textPrimary.copy(alpha = 0.86f),
    unfocusedTextColor = MaterialTheme.memoryColors.textPrimary.copy(alpha = 0.86f),
    focusedBorderColor = MaterialTheme.memoryColors.accent.copy(alpha = 0.4f),
    unfocusedBorderColor = MaterialTheme.memoryColors.textPrimary.copy(alpha = 0.1f),
    focusedContainerColor = MaterialTheme.memoryColors.textPrimary.copy(alpha = 0.05f),
    unfocusedContainerColor = MaterialTheme.memoryColors.textPrimary.copy(alpha = 0.05f),
    focusedLabelColor = MaterialTheme.memoryColors.accent,
    unfocusedLabelColor = MaterialTheme.memoryColors.textPrimary.copy(alpha = 0.35f),
    cursorColor = MaterialTheme.memoryColors.accent,
)

internal data class MemoryMenuOption(
    val title: String,
    val color: Color,
    val action: () -> Unit,
)

@Composable
internal fun MemoryMenuField(
    selectedText: String,
    options: List<MemoryMenuOption>,
    modifier: Modifier = Modifier,
    selectedColor: Color = MaterialTheme.memoryColors.textPrimary.copy(alpha = 0.9f),
) {
    var expanded by remember(selectedText, options.size) { mutableStateOf(false) }

    Box(modifier = modifier) {
        Surface(
            onClick = { expanded = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp),
            color = MaterialTheme.memoryColors.textPrimary.copy(alpha = 0.05f),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MaterialTheme.memoryColors.textPrimary.copy(alpha = 0.06f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = selectedText,
                    modifier = Modifier.weight(1f),
                    color = selectedColor,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.memoryColors.textPrimary.copy(alpha = 0.3f),
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .background(MaterialTheme.memoryColors.popover, RoundedCornerShape(10.dp))
                .border(1.dp, MaterialTheme.memoryColors.textPrimary.copy(alpha = 0.1f), RoundedCornerShape(10.dp)),
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            option.title,
                            color = option.color,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = {
                        expanded = false
                        option.action()
                    },
                )
            }
        }
    }
}
