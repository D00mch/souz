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
import souz.sharedui.generated.resources.Res
import souz.sharedui.generated.resources.memory_action_delete
import souz.sharedui.generated.resources.memory_empty
import souz.sharedui.generated.resources.memory_error_inline_dismiss
import souz.sharedui.generated.resources.memory_filter_kind_all
import souz.sharedui.generated.resources.memory_filter_query
import souz.sharedui.generated.resources.memory_loading

internal object MemoryUiColors {
    val Screen = Color(0xFF161616)
    val Panel = Color(0xFF1A1A1A)
    val Popover = Color(0xFF1E1E1E)
    val Card = Color.White.copy(alpha = 0.04f)
    val CardHover = Color.White.copy(alpha = 0.06f)
    val SelectedCard = Color(0xFF00D9B3).copy(alpha = 0.07f)
    val CardBorder = Color.White.copy(alpha = 0.06f)
    val SelectedBorder = Color(0xFF00D9B3).copy(alpha = 0.25f)
    val Accent = Color(0xFF00D9B3)
    val Divider = Color.White.copy(alpha = 0.07f)
    val TextPrimary = Color.White
    val Warning = Color(0xFFFBBF24)
    val Danger = Color(0xFFFB923C)
    val Transparent = Color.Transparent
}

internal data class MemoryKindStyle(
    val color: Color,
    val background: Color,
)

internal fun MemoryFactKind.kindStyle(): MemoryKindStyle =
    when (this) {
        MemoryFactKind.SEMANTIC -> MemoryKindStyle(Color(0xFF63B3ED), Color(0xFF63B3ED).copy(alpha = 0.12f))
        MemoryFactKind.PREFERENCE -> MemoryKindStyle(Color(0xFF00D9B3), Color(0xFF00D9B3).copy(alpha = 0.12f))
        MemoryFactKind.PROCEDURE -> MemoryKindStyle(Color(0xFFA78BFA), Color(0xFFA78BFA).copy(alpha = 0.12f))
        MemoryFactKind.PROJECT_RULE -> MemoryKindStyle(Color(0xFFFB923C), Color(0xFFFB923C).copy(alpha = 0.12f))
        MemoryFactKind.EPISODE_NOTE -> MemoryKindStyle(Color(0xFF94A3B8), Color(0xFF94A3B8).copy(alpha = 0.12f))
        MemoryFactKind.PROJECT_DECISION -> MemoryKindStyle(Color(0xFFFBBF24), Color(0xFFFBBF24).copy(alpha = 0.12f))
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
                color = MemoryUiColors.TextPrimary.copy(alpha = 0.2f),
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
            color = MemoryUiColors.TextPrimary.copy(alpha = 0.84f),
            fontSize = 12.5.sp,
        ),
        cursorBrush = SolidColor(MemoryUiColors.Accent),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(MemoryUiColors.TextPrimary.copy(alpha = 0.05f))
                    .border(1.dp, MemoryUiColors.TextPrimary.copy(alpha = 0.1f), RoundedCornerShape(11.dp))
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = MemoryUiColors.TextPrimary.copy(alpha = 0.32f),
                    modifier = Modifier.size(16.dp),
                )
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (query.isBlank()) {
                        Text(
                            text = stringResource(Res.string.memory_filter_query),
                            color = MemoryUiColors.TextPrimary.copy(alpha = 0.28f),
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
                            tint = MemoryUiColors.TextPrimary.copy(alpha = 0.35f),
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
        selectedColor = selectedKind?.kindStyle()?.color ?: MemoryUiColors.TextPrimary.copy(alpha = 0.5f),
        modifier = modifier,
        options = listOf(
            MemoryMenuOption(
                title = allLabel,
                color = MemoryUiColors.TextPrimary.copy(alpha = 0.5f),
                action = { onSelect(null) },
            )
        ) + MemoryFactKind.entries.map { kind ->
            MemoryMenuOption(kind.label(), kind.kindStyle().color) { onSelect(kind) }
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
            .background(MemoryUiColors.TextPrimary.copy(alpha = 0.05f))
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MemoryStatusFilter.entries.forEach { status ->
            val active = selected == status
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (active) MemoryUiColors.TextPrimary.copy(alpha = 0.1f) else MemoryUiColors.Transparent)
                    .clickable { onSelected(status) }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = status.label(),
                    color = MemoryUiColors.TextPrimary.copy(alpha = if (active) 0.8f else 0.3f),
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
        selected -> MemoryUiColors.SelectedCard
        hovered -> MemoryUiColors.CardHover
        else -> MemoryUiColors.Card
    }
    val border = if (selected) MemoryUiColors.SelectedBorder else MemoryUiColors.CardBorder

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
                        tint = MemoryUiColors.Accent,
                        modifier = Modifier.size(12.dp),
                    )
                }
                Text(
                    text = fact.title,
                    color = MemoryUiColors.TextPrimary.copy(alpha = 0.9f),
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
                    color = MemoryUiColors.TextPrimary.copy(alpha = 0.3f),
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
                            tint = MemoryUiColors.TextPrimary.copy(alpha = 0.45f),
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
            color = MemoryUiColors.TextPrimary.copy(alpha = 0.45f),
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
                    color = MemoryUiColors.TextPrimary.copy(alpha = 0.3f),
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
            .background(style.background)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(style.color, RoundedCornerShape(999.dp)),
        )
        Text(
            text = kind.label(),
            color = style.color,
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
                .background(MemoryUiColors.TextPrimary.copy(alpha = 0.1f)),
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

internal fun confidenceColor(confidence: Float): Color =
    when {
        confidence >= 0.85f -> MemoryUiColors.Accent
        confidence >= 0.65f -> MemoryUiColors.Warning
        else -> MemoryUiColors.Danger
    }

@Composable
internal fun MemoryInlineError(
    message: String,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MemoryUiColors.Danger.copy(alpha = 0.12f))
            .border(1.dp, MemoryUiColors.Danger.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            modifier = Modifier.weight(1f),
            color = MemoryUiColors.Danger,
            fontSize = 12.sp,
        )
        Text(
            text = stringResource(Res.string.memory_error_inline_dismiss),
            color = MemoryUiColors.Danger,
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
            color = MemoryUiColors.TextPrimary.copy(alpha = 0.45f),
            fontSize = 13.sp,
        )
    }
}

@Composable
internal fun memoryTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = MemoryUiColors.TextPrimary.copy(alpha = 0.86f),
    unfocusedTextColor = MemoryUiColors.TextPrimary.copy(alpha = 0.86f),
    focusedBorderColor = MemoryUiColors.Accent.copy(alpha = 0.4f),
    unfocusedBorderColor = MemoryUiColors.TextPrimary.copy(alpha = 0.1f),
    focusedContainerColor = MemoryUiColors.TextPrimary.copy(alpha = 0.05f),
    unfocusedContainerColor = MemoryUiColors.TextPrimary.copy(alpha = 0.05f),
    focusedLabelColor = MemoryUiColors.Accent,
    unfocusedLabelColor = MemoryUiColors.TextPrimary.copy(alpha = 0.35f),
    cursorColor = MemoryUiColors.Accent,
)

internal data class MemoryMenuOption(
    val title: String,
    val color: Color = MemoryUiColors.TextPrimary.copy(alpha = 0.9f),
    val action: () -> Unit,
)

@Composable
internal fun MemoryMenuField(
    selectedText: String,
    options: List<MemoryMenuOption>,
    modifier: Modifier = Modifier,
    selectedColor: Color = MemoryUiColors.TextPrimary.copy(alpha = 0.9f),
) {
    var expanded by remember(selectedText, options.size) { mutableStateOf(false) }

    Box(modifier = modifier) {
        Surface(
            onClick = { expanded = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp),
            color = MemoryUiColors.TextPrimary.copy(alpha = 0.05f),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MemoryUiColors.TextPrimary.copy(alpha = 0.06f)),
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
                    tint = MemoryUiColors.TextPrimary.copy(alpha = 0.3f),
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .background(MemoryUiColors.Popover, RoundedCornerShape(10.dp))
                .border(1.dp, MemoryUiColors.TextPrimary.copy(alpha = 0.1f), RoundedCornerShape(10.dp)),
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
