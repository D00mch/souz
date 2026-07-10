package ru.souz.ui.memory

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.compose.localDI
import ru.souz.memory.MemoryMaintenanceBlockReason
import ru.souz.memory.MemoryMaintenanceMode
import ru.souz.ui.common.DraggableWindowArea
import ru.souz.ui.common.LiquidGlassPreset
import ru.souz.ui.common.RealLiquidGlassCard
import souz.sharedui.generated.resources.Res
import souz.sharedui.generated.resources.button_close
import souz.sharedui.generated.resources.memory_create
import souz.sharedui.generated.resources.memory_dreamer_block_actions
import souz.sharedui.generated.resources.memory_dreamer_block_disabled
import souz.sharedui.generated.resources.memory_dreamer_block_pending
import souz.sharedui.generated.resources.memory_dreamer_completed
import souz.sharedui.generated.resources.memory_dreamer_error
import souz.sharedui.generated.resources.memory_dreamer_mode_local
import souz.sharedui.generated.resources.memory_dreamer_mode_off
import souz.sharedui.generated.resources.memory_dreamer_model_auto
import souz.sharedui.generated.resources.memory_dreamer_no_changes
import souz.sharedui.generated.resources.memory_dreamer_pending_blocked
import souz.sharedui.generated.resources.memory_dreamer_retry
import souz.sharedui.generated.resources.memory_dreamer_run
import souz.sharedui.generated.resources.memory_dreamer_running
import souz.sharedui.generated.resources.memory_dreamer_title
import souz.sharedui.generated.resources.memory_subtitle
import souz.sharedui.generated.resources.memory_title

@Composable
fun MemoryScreen(
    onClose: () -> Unit,
    onShowSnack: (String) -> Unit = {},
) {
    val di = localDI()
    val viewModel = viewModel { MemoryViewModel(di) }
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is MemoryEffect.ShowError -> onShowSnack(effect.message)
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.onAction(MemoryAction.Load)
    }

    MemoryScreen(
        state = state,
        onAction = viewModel::onAction,
        onClose = onClose,
    )
}

@Composable
fun MemoryScreen(
    state: MemoryUiState,
    onAction: (MemoryAction) -> Unit,
    onClose: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val isFocused = LocalWindowInfo.current.isWindowFocused

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    when {
                        state.editor != null -> onAction(MemoryAction.CloseDialog)
                        state.confirm != null -> onAction(MemoryAction.CancelConfirmAction)
                        state.detailsFactId != null -> onAction(MemoryAction.CloseDetails)
                        else -> onClose()
                    }
                    true
                } else {
                    false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        RealLiquidGlassCard(
            modifier = Modifier.fillMaxSize(),
            isWindowFocused = isFocused,
            preset = LiquidGlassPreset.Hero,
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
            ) {
                DraggableWindowArea {
                    MemoryHeader(
                        onCreate = { onAction(MemoryAction.OpenCreateDialog) },
                        onClose = onClose,
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MemoryFilters(
                        filters = state.filters,
                        visibleCount = state.facts.size,
                        onFiltersChange = { onAction(MemoryAction.ChangeFilters(it)) },
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        MemoryFactsContent(
                            state = state,
                            onAction = onAction,
                        )

                        if (state.detailsFactId != null || state.selectedFact != null || state.isDetailsLoading) {
                            MemoryFactDetailsPanel(
                                state = state,
                                onAction = onAction,
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .width(296.dp)
                                    .fillMaxHeight(),
                            )
                        }
                    }
                }

                MemoryDreamerFooter(
                    state = state.maintenance,
                    onAction = onAction,
                )
            }
        }

        state.editor?.let { editor ->
            MemoryFactEditorDialog(
                editor = editor,
                isSaving = state.isSaving,
                onDismiss = { onAction(MemoryAction.CloseDialog) },
                onSave = { onAction(MemoryAction.SaveFact(it)) },
            )
        }

        state.confirm?.let { confirmAction ->
            MemoryConfirmDialog(
                action = confirmAction,
                factTitle = state.factTitle(confirmAction.factId),
                onConfirm = { onAction(MemoryAction.ConfirmAction) },
                onDismiss = { onAction(MemoryAction.CancelConfirmAction) },
            )
        }
    }
}

@Composable
private fun MemoryHeader(
    onCreate: () -> Unit,
    onClose: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(78.dp)
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Psychology,
                    contentDescription = null,
                    tint = MemoryUiColors.Accent,
                    modifier = Modifier.size(18.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = stringResource(Res.string.memory_title),
                        color = MemoryUiColors.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(Res.string.memory_subtitle),
                        color = MemoryUiColors.TextPrimary.copy(alpha = 0.35f),
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AccentGhostButton(
                    text = stringResource(Res.string.memory_create),
                    onClick = onCreate,
                )
                IconButton(onClick = onClose, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(Res.string.button_close),
                        tint = MemoryUiColors.TextPrimary.copy(alpha = 0.3f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        HorizontalDivider(color = MemoryUiColors.Divider)
    }
}

@Composable
private fun AccentGhostButton(
    text: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MemoryUiColors.Accent.copy(alpha = 0.12f),
        contentColor = MemoryUiColors.Accent,
        border = BorderStroke(1.dp, MemoryUiColors.Accent.copy(alpha = 0.25f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(13.dp))
            Text(text = text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun MemoryDreamerFooter(
    state: MemoryMaintenanceUiState,
    onAction: (MemoryAction) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        HorizontalDivider(color = MemoryUiColors.Divider.copy(alpha = 0.85f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                DreamerFooterButton(
                    state = state,
                    onClick = { expanded = true },
                )
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.width(320.dp),
                    shape = RoundedCornerShape(12.dp),
                    containerColor = MemoryUiColors.Popover,
                    tonalElevation = 0.dp,
                    shadowElevation = 8.dp,
                    border = BorderStroke(1.dp, MemoryUiColors.TextPrimary.copy(alpha = 0.1f)),
                ) {
                    DreamerPopover(
                        state = state,
                        onAction = onAction,
                    )
                }
            }
        }
    }
}

@Composable
private fun DreamerFooterButton(
    state: MemoryMaintenanceUiState,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 4.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(dreamerDotColor(state), RoundedCornerShape(999.dp)),
        )
        Text(
            text = "${stringResource(Res.string.memory_dreamer_title)} · ${dreamerModeLabel(state.mode)}",
            color = MemoryUiColors.TextPrimary.copy(alpha = if (hovered) 0.6f else 0.4f),
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium,
        )
        Icon(
            imageVector = Icons.Rounded.Settings,
            contentDescription = null,
            tint = MemoryUiColors.TextPrimary.copy(alpha = if (hovered) 0.55f else 0.32f),
            modifier = Modifier.size(12.dp),
        )
    }
}

@Composable
private fun DreamerPopover(
    state: MemoryMaintenanceUiState,
    onAction: (MemoryAction) -> Unit,
) {
    Column(
        modifier = Modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.memory_dreamer_title),
                color = MemoryUiColors.TextPrimary.copy(alpha = 0.9f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stringResource(
                    Res.string.memory_dreamer_pending_blocked,
                    state.pendingClusters,
                    state.blockedClusters,
                ),
                color = MemoryUiColors.TextPrimary.copy(alpha = 0.3f),
                fontSize = 11.sp,
            )
        }

        MemoryModeSegments(
            selected = state.mode,
            onSelected = { onAction(MemoryAction.SelectDreamerMode(it)) },
        )

        DreamerModelSelector(
            state = state,
            onSelected = { onAction(MemoryAction.SelectDreamerModel(it)) },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MemoryDreamerStatusText(state, modifier = Modifier.weight(1f))
            Button(
                onClick = { onAction(MemoryAction.RunDreamerNow) },
                enabled = state.canRunNow,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MemoryUiColors.Accent,
                    contentColor = MemoryUiColors.Screen,
                    disabledContainerColor = MemoryUiColors.TextPrimary.copy(alpha = 0.08f),
                    disabledContentColor = MemoryUiColors.TextPrimary.copy(alpha = 0.28f),
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                shape = RoundedCornerShape(8.dp),
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text(stringResource(Res.string.memory_dreamer_run), fontSize = 11.5.sp)
            }
        }

        if (state.isRunningNow) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Schedule,
                    contentDescription = null,
                    tint = MemoryUiColors.Warning,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = stringResource(Res.string.memory_dreamer_running),
                    color = MemoryUiColors.Warning,
                    fontSize = 11.5.sp,
                )
            }
        }
    }
}

@Composable
private fun DreamerModelSelector(
    state: MemoryMaintenanceUiState,
    onSelected: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            color = MemoryUiColors.TextPrimary.copy(alpha = 0.05f),
            contentColor = MemoryUiColors.TextPrimary.copy(alpha = 0.7f),
            shape = RoundedCornerShape(8.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.selectedModel?.displayName
                        ?: stringResource(Res.string.memory_dreamer_model_auto),
                    modifier = Modifier.weight(1f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(296.dp),
            shape = RoundedCornerShape(8.dp),
            containerColor = MemoryUiColors.Popover,
            tonalElevation = 0.dp,
            border = BorderStroke(1.dp, MemoryUiColors.TextPrimary.copy(alpha = 0.1f)),
        ) {
            listOf(null to stringResource(Res.string.memory_dreamer_model_auto))
                .plus(state.availableModels.map { it.alias to it.displayName })
                .forEach { (alias, label) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = label,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        onClick = {
                            expanded = false
                            onSelected(alias)
                        },
                    )
                }
        }
    }
}

@Composable
private fun MemoryModeSegments(
    selected: MemoryMaintenanceMode,
    onSelected: (MemoryMaintenanceMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MemoryUiColors.TextPrimary.copy(alpha = 0.05f))
            .padding(2.dp),
    ) {
        listOf(
            MemoryMaintenanceMode.OFF to stringResource(Res.string.memory_dreamer_mode_off),
            MemoryMaintenanceMode.LOCAL_ONLY to stringResource(Res.string.memory_dreamer_mode_local),
        ).forEach { (mode, label) ->
            val active = selected == mode
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (active) MemoryUiColors.TextPrimary.copy(alpha = 0.1f) else MemoryUiColors.Transparent)
                    .clickable { onSelected(mode) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = MemoryUiColors.TextPrimary.copy(alpha = if (active) 0.8f else 0.3f),
                    fontSize = 11.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun MemoryDreamerStatusText(
    state: MemoryMaintenanceUiState,
    modifier: Modifier = Modifier,
) {
    val status = dreamerStatusText(state)
    Text(
        text = status,
        modifier = modifier,
        color = MemoryUiColors.TextPrimary.copy(alpha = 0.32f),
        fontSize = 11.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun dreamerStatusText(state: MemoryMaintenanceUiState): String {
    return when (state.runOutcome) {
        MemoryMaintenanceRunOutcome.RUNNING -> stringResource(Res.string.memory_dreamer_running)
        MemoryMaintenanceRunOutcome.ERROR -> stringResource(Res.string.memory_dreamer_error, state.lastErrorCode.orEmpty())
        MemoryMaintenanceRunOutcome.RETRY_SCHEDULED -> stringResource(
            Res.string.memory_dreamer_retry,
            state.lastAttemptedAt?.maintenanceLabel().orEmpty(),
        )
        MemoryMaintenanceRunOutcome.COMPLETED -> stringResource(
            Res.string.memory_dreamer_completed,
            state.lastCompletedAt?.maintenanceLabel().orEmpty(),
        )
        MemoryMaintenanceRunOutcome.NO_CHANGES -> stringResource(
            Res.string.memory_dreamer_no_changes,
            state.lastAttemptedAt?.maintenanceLabel().orEmpty(),
        )
        MemoryMaintenanceRunOutcome.IDLE -> state.blockedReason?.let { dreamerBlockReasonLabel(it) } ?: stringResource(
            Res.string.memory_dreamer_pending_blocked,
            state.pendingClusters,
            state.blockedClusters,
        )
    }
}

@Composable
private fun dreamerModeLabel(mode: MemoryMaintenanceMode): String = when (mode) {
    MemoryMaintenanceMode.OFF -> stringResource(Res.string.memory_dreamer_mode_off)
    MemoryMaintenanceMode.LOCAL_ONLY -> stringResource(Res.string.memory_dreamer_mode_local)
}

private fun dreamerDotColor(state: MemoryMaintenanceUiState) = when {
    state.isRunningNow -> MemoryUiColors.Warning
    state.mode == MemoryMaintenanceMode.OFF -> MemoryUiColors.TextPrimary.copy(alpha = 0.25f)
    else -> MemoryUiColors.Accent
}

@Composable
private fun dreamerBlockReasonLabel(reason: MemoryMaintenanceBlockReason): String =
    when (reason) {
        MemoryMaintenanceBlockReason.DREAMER_DISABLED -> stringResource(Res.string.memory_dreamer_block_disabled)
        MemoryMaintenanceBlockReason.NO_PENDING_CLUSTERS -> stringResource(Res.string.memory_dreamer_block_pending)
        MemoryMaintenanceBlockReason.NO_DETERMINISTIC_ACTIONS -> stringResource(Res.string.memory_dreamer_block_actions)
    }
