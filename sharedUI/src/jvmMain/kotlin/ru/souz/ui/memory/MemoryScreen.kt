package ru.souz.ui.memory

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.compose.localDI
import ru.souz.memory.MemoryFactKind
import ru.souz.memory.MemoryFactStatus
import ru.souz.ui.common.ConfirmDialog
import ru.souz.ui.common.ConfirmDialogType
import ru.souz.ui.components.LabeledTextField
import ru.souz.ui.common.DraggableWindowArea
import ru.souz.ui.glassColors
import ru.souz.ui.main.RealLiquidGlassCard
import souz.sharedui.generated.resources.Res
import souz.sharedui.generated.resources.button_close
import souz.sharedui.generated.resources.memory_action_delete
import souz.sharedui.generated.resources.memory_action_edit
import souz.sharedui.generated.resources.memory_action_pin
import souz.sharedui.generated.resources.memory_action_retire
import souz.sharedui.generated.resources.memory_action_unpin
import souz.sharedui.generated.resources.memory_button_save
import souz.sharedui.generated.resources.memory_button_saving
import souz.sharedui.generated.resources.memory_confirm_delete_message
import souz.sharedui.generated.resources.memory_confirm_delete_title
import souz.sharedui.generated.resources.memory_confirm_retire_message
import souz.sharedui.generated.resources.memory_confirm_retire_title
import souz.sharedui.generated.resources.memory_create
import souz.sharedui.generated.resources.memory_details_created_at
import souz.sharedui.generated.resources.memory_details_created_by
import souz.sharedui.generated.resources.memory_details_evidence
import souz.sharedui.generated.resources.memory_details_loading
import souz.sharedui.generated.resources.memory_details_scope
import souz.sharedui.generated.resources.memory_details_select
import souz.sharedui.generated.resources.memory_details_slot_key
import souz.sharedui.generated.resources.memory_details_status
import souz.sharedui.generated.resources.memory_details_supersedes
import souz.sharedui.generated.resources.memory_details_title
import souz.sharedui.generated.resources.memory_details_updated_at
import souz.sharedui.generated.resources.memory_editor_body
import souz.sharedui.generated.resources.memory_editor_create_title
import souz.sharedui.generated.resources.memory_editor_edit_title
import souz.sharedui.generated.resources.memory_editor_kind
import souz.sharedui.generated.resources.memory_editor_pinned
import souz.sharedui.generated.resources.memory_editor_slot_key
import souz.sharedui.generated.resources.memory_editor_title
import souz.sharedui.generated.resources.memory_empty
import souz.sharedui.generated.resources.memory_evidence_empty
import souz.sharedui.generated.resources.memory_error_inline_dismiss
import souz.sharedui.generated.resources.memory_filter_kind
import souz.sharedui.generated.resources.memory_filter_kind_all
import souz.sharedui.generated.resources.memory_filter_query
import souz.sharedui.generated.resources.memory_filter_scope
import souz.sharedui.generated.resources.memory_filter_status
import souz.sharedui.generated.resources.memory_filter_status_active
import souz.sharedui.generated.resources.memory_filter_status_all
import souz.sharedui.generated.resources.memory_filter_status_deleted
import souz.sharedui.generated.resources.memory_filter_status_retired
import souz.sharedui.generated.resources.memory_loading
import souz.sharedui.generated.resources.memory_scope_all
import souz.sharedui.generated.resources.memory_scope_global
import souz.sharedui.generated.resources.memory_subtitle
import souz.sharedui.generated.resources.memory_title
import souz.sharedui.generated.resources.memory_badge_pinned
import souz.sharedui.generated.resources.memory_details_confidence
import souz.sharedui.generated.resources.memory_kind_semantic
import souz.sharedui.generated.resources.memory_kind_preference
import souz.sharedui.generated.resources.memory_kind_procedure
import souz.sharedui.generated.resources.memory_kind_project_rule
import souz.sharedui.generated.resources.memory_kind_episode_note
import souz.sharedui.generated.resources.memory_kind_project_decision
import souz.sharedui.generated.resources.memory_created_by_manual
import souz.sharedui.generated.resources.memory_created_by_auto
import souz.sharedui.generated.resources.memory_created_by_system
import souz.sharedui.generated.resources.memory_scope_chat_format
import souz.sharedui.generated.resources.memory_editor_error_title_required
import souz.sharedui.generated.resources.memory_editor_error_body_required
import souz.sharedui.generated.resources.memory_editor_advanced

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
    val windowInfo = LocalWindowInfo.current
    val isFocused = windowInfo.isWindowFocused
    val focusRequester = remember { FocusRequester() }

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
                        state.confirmAction != null -> onAction(MemoryAction.CancelConfirmAction)
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
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                DraggableWindowArea {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(Res.string.memory_title),
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.glassColors.textPrimary,
                            )
                            Text(
                                text = stringResource(Res.string.memory_subtitle),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.7f),
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val createInteraction = remember { MutableInteractionSource() }
                            val isCreateHovered by createInteraction.collectIsHoveredAsState()

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        brush = Brush.linearGradient(
                                            colors = if (isCreateHovered) {
                                                listOf(Color(0x26FFFFFF), Color(0x0DFFFFFF))
                                            } else {
                                                listOf(Color(0x14FFFFFF), Color(0x05FFFFFF))
                                            },
                                        ),
                                    )
                                    .border(
                                        1.dp,
                                        if (isCreateHovered) Color(0x4DFFFFFF) else Color(0x33FFFFFF),
                                        RoundedCornerShape(12.dp),
                                    )
                                    .clickable(
                                        interactionSource = createInteraction,
                                        indication = null,
                                    ) { onAction(MemoryAction.OpenCreateDialog) }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Add,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.glassColors.textPrimary,
                                    )
                                    Text(
                                        text = stringResource(Res.string.memory_create),
                                        color = MaterialTheme.glassColors.textPrimary,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                            IconButton(onClick = onClose) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(Res.string.button_close),
                                    tint = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.8f),
                                )
                            }
                        }
                    }
                }

                MemoryFilters(
                    filters = state.filters,
                    onFiltersChange = { onAction(MemoryAction.ChangeFilters(it)) },
                )

                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    ) {
                        MemoryFactsContent(
                            state = state,
                            onAction = onAction,
                        )
                    }

                    if (state.detailsFactId != null || state.selectedFact != null || state.isDetailsLoading) {
                        Box(
                            modifier = Modifier
                                .width(360.dp)
                                .fillMaxHeight(),
                        ) {
                            MemoryFactDetailsPanel(
                                state = state,
                                onAction = onAction,
                            )
                        }
                    }
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

            state.confirmAction?.let { confirmAction ->
                MemoryConfirmDialog(
                    action = confirmAction,
                    onConfirm = { onAction(MemoryAction.ConfirmAction) },
                    onDismiss = { onAction(MemoryAction.CancelConfirmAction) },
                )
            }
        }
    }
}

@Composable
private fun MemoryFilters(
    filters: MemoryFiltersUi,
    onFiltersChange: (MemoryFiltersUi) -> Unit,
) {
    val allScopesLabel = stringResource(Res.string.memory_scope_all)
    val globalScopeLabel = stringResource(Res.string.memory_scope_global)
    val selectedScopeLabel = when {
        filters.scopeType.isBlank() && filters.scopeId.isBlank() -> allScopesLabel
        isGlobalScope(filters.scopeType, filters.scopeId) -> globalScopeLabel
        else -> memoryScopeLabel(filters.scopeType, filters.scopeId)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = filters.query,
            onValueChange = { onFiltersChange(filters.copy(query = it)) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            label = { Text(stringResource(Res.string.memory_filter_query)) },
            shape = RoundedCornerShape(12.dp),
            colors = memoryTextFieldColors(),
        )

        MemoryMenuField(
            label = stringResource(Res.string.memory_filter_status),
            selectedText = filters.status.label(),
            modifier = Modifier.width(150.dp),
            options = MemoryStatusFilter.entries.map { status ->
                status.label() to { onFiltersChange(filters.copy(status = status)) }
            },
        )

        MemoryMenuField(
            label = stringResource(Res.string.memory_filter_kind),
            selectedText = filters.kind?.label() ?: stringResource(Res.string.memory_filter_kind_all),
            modifier = Modifier.width(170.dp),
            options = listOf(
                stringResource(Res.string.memory_filter_kind_all) to {
                    onFiltersChange(filters.copy(kind = null))
                }
            ) + MemoryFactKind.entries.map { kind ->
                kind.label() to { onFiltersChange(filters.copy(kind = kind)) }
            },
        )

        MemoryMenuField(
            label = stringResource(Res.string.memory_filter_scope),
            selectedText = selectedScopeLabel,
            modifier = Modifier.width(170.dp),
            options = listOf(
                allScopesLabel to {
                    onFiltersChange(filters.copy(scopeType = "", scopeId = ""))
                },
                globalScopeLabel to {
                    onFiltersChange(
                        filters.copy(
                            scopeType = DEFAULT_SCOPE_TYPE,
                            scopeId = DEFAULT_SCOPE_ID,
                        )
                    )
                },
            ),
        )
    }
}

@Composable
private fun MemoryFactsContent(
    state: MemoryUiState,
    onAction: (MemoryAction) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize(),
    ) {
        if (state.error != null && state.facts.isNotEmpty()) {
            MemoryInlineError(
                message = state.error,
                onDismiss = { onAction(MemoryAction.ClearError) },
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
        }

        when {
            state.isLoading && state.facts.isEmpty() -> {
                MemoryCenteredText(stringResource(Res.string.memory_loading))
            }

            state.error != null && state.facts.isEmpty() -> {
                MemoryCenteredText(state.error)
            }

            state.facts.isEmpty() -> {
                MemoryCenteredText(stringResource(Res.string.memory_empty))
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(state.facts, key = { _, fact -> fact.id }) { index, fact ->
                        MemoryFactRow(fact = fact, onAction = onAction)
                        if (index < state.facts.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 4.dp),
                                color = Color.White.copy(alpha = 0.08f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryFactRow(
    fact: MemoryFactUi,
    onAction: (MemoryAction) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onAction(MemoryAction.OpenDetails(fact.id)) }
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = fact.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.glassColors.textPrimary,
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = fact.updatedAtShortLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.55f),
                )
                IconButton(
                    onClick = { onAction(MemoryAction.AskDelete(fact.id)) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = stringResource(Res.string.memory_action_delete),
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        Text(
            text = "${fact.kindEnum.label()} · ${memoryScopeLabel(fact.scopeType, fact.scopeId)} · ${fact.confidenceLabel}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.58f),
        )

        Text(
            text = fact.bodyPreview,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.82f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MemoryFactDetailsPanel(
    state: MemoryUiState,
    onAction: (MemoryAction) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .panelSurface()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.memory_details_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.glassColors.textPrimary,
            )
            IconButton(onClick = { onAction(MemoryAction.CloseDetails) }) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(Res.string.button_close),
                    tint = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.8f),
                )
            }
        }

        when {
            state.isDetailsLoading -> {
                MemoryCenteredText(stringResource(Res.string.memory_details_loading))
            }

            state.selectedFact == null -> {
                MemoryCenteredText(stringResource(Res.string.memory_details_select))
            }

            else -> state.selectedFact?.let { fact ->
                val scrollState = rememberScrollState()

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        text = fact.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.glassColors.textPrimary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (fact.pinned) {
                            MemoryBadge(text = stringResource(Res.string.memory_badge_pinned), tint = MaterialTheme.colorScheme.primary)
                        }
                        MemoryBadge(text = createdByLabel(fact.createdByLabel), tint = Color(0xFF82B1FF))
                        if (fact.statusEnum != MemoryFactStatus.ACTIVE) {
                            MemoryBadge(text = fact.statusEnum.label(), tint = Color(0xFFFFB86C))
                        }
                    }

                    Text(
                        text = fact.body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.88f),
                    )

                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                    MemoryDetailItem(stringResource(Res.string.memory_editor_kind), fact.kindEnum.label())
                    MemoryDetailItem(stringResource(Res.string.memory_details_scope), memoryScopeLabel(fact.scopeType, fact.scopeId))
                    MemoryDetailItem(stringResource(Res.string.memory_details_status), fact.statusEnum.label())
                    MemoryDetailItem(stringResource(Res.string.memory_details_confidence), fact.confidenceLabel)
                    MemoryDetailItem(stringResource(Res.string.memory_details_created_by), createdByLabel(fact.createdByLabel))
                    MemoryDetailItem(stringResource(Res.string.memory_details_created_at), fact.createdAtLabel)
                    MemoryDetailItem(stringResource(Res.string.memory_details_updated_at), fact.updatedAtLabel)
                    fact.slotKey?.let {
                        MemoryDetailItem(stringResource(Res.string.memory_details_slot_key), it)
                    }
                    fact.supersedesFactId?.let {
                        MemoryDetailItem(stringResource(Res.string.memory_details_supersedes), it)
                    }

                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                    Text(
                        text = stringResource(Res.string.memory_details_evidence),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.glassColors.textPrimary,
                    )

                    if (fact.evidence.isEmpty()) {
                        Text(
                            text = stringResource(Res.string.memory_evidence_empty),
                            color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        fact.evidence.forEach { evidence ->
                            MemoryEvidenceCard(evidence = evidence)
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        MemoryActionButton(stringResource(Res.string.memory_action_edit)) {
                            onAction(MemoryAction.OpenEditDialog(fact.id))
                        }
                        MemoryActionButton(
                            if (fact.pinned) {
                                stringResource(Res.string.memory_action_unpin)
                            } else {
                                stringResource(Res.string.memory_action_pin)
                            }
                        ) {
                            onAction(MemoryAction.SetPinned(fact.id, !fact.pinned))
                        }
                        if (fact.statusEnum != MemoryFactStatus.RETIRED && fact.statusEnum != MemoryFactStatus.DELETED) {
                            MemoryActionButton(stringResource(Res.string.memory_action_retire)) {
                                onAction(MemoryAction.AskRetire(fact.id))
                            }
                        }
                        if (fact.statusEnum != MemoryFactStatus.DELETED) {
                            MemoryActionButton(
                                text = stringResource(Res.string.memory_action_delete),
                                tint = MaterialTheme.colorScheme.error,
                            ) {
                                onAction(MemoryAction.AskDelete(fact.id))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryEvidenceCard(
    evidence: MemoryEvidenceUi,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = evidence.text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.glassColors.textPrimary,
        )
        if (evidence.sourceText != evidence.text) {
            Text(
                text = evidence.sourceText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.68f),
                lineHeight = 18.sp,
            )
        }
        Text(
            text = listOfNotNull(
                evidence.sourceType,
                evidence.sourceRef,
                evidence.createdAtLabel,
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.55f),
        )
    }
}

@Composable
private fun MemoryFactEditorDialog(
    editor: MemoryEditorState,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSave: (MemoryEditorInput) -> Unit,
) {
    var title by remember(editor) { mutableStateOf(editor.input.title) }
    var body by remember(editor) { mutableStateOf(editor.input.body) }
    var kind by remember(editor) { mutableStateOf(editor.input.kind) }
    val scopeType = editor.input.scopeType.ifBlank { DEFAULT_SCOPE_TYPE }
    val scopeId = editor.input.scopeId.ifBlank { DEFAULT_SCOPE_ID }
    var slotKey by remember(editor) { mutableStateOf(editor.input.slotKey.orEmpty()) }
    var pinned by remember(editor) { mutableStateOf(editor.input.pinned) }
    val scopeLabel = memoryScopeLabel(scopeType, scopeId)
    var showAdvanced by remember { mutableStateOf(!editor.input.slotKey.isNullOrBlank()) }
    var showValidationErrors by remember { mutableStateOf(false) }

    val titleRequiredMsg = stringResource(Res.string.memory_editor_error_title_required)
    val bodyRequiredMsg = stringResource(Res.string.memory_editor_error_body_required)
    val validationMessage = remember(title, body, titleRequiredMsg, bodyRequiredMsg) {
        when {
            title.isBlank() -> titleRequiredMsg
            body.isBlank() -> bodyRequiredMsg
            else -> null
        }
    }

    ConfirmDialog(
        type = ConfirmDialogType.INFO,
        title = if (editor.mode == MemoryEditorMode.CREATE) {
            stringResource(Res.string.memory_editor_create_title)
        } else {
            stringResource(Res.string.memory_editor_edit_title)
        },
        message = "${stringResource(Res.string.memory_details_scope)}: $scopeLabel",
        dialogMaxWidth = 540.dp,
        confirmText = if (isSaving) {
            stringResource(Res.string.memory_button_saving)
        } else {
            stringResource(Res.string.memory_button_save)
        },
        confirmEnabled = !isSaving,
        onConfirm = {
            if (validationMessage != null) {
                showValidationErrors = true
            } else {
                onSave(
                    MemoryEditorInput(
                        factId = editor.input.factId,
                        title = title,
                        body = body,
                        kind = kind,
                        scopeType = scopeType,
                        scopeId = scopeId,
                        slotKey = slotKey,
                        pinned = pinned,
                    )
                )
            }
        },
        onDismiss = onDismiss,
        detailsContent = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LabeledTextField(
                    label = stringResource(Res.string.memory_editor_title),
                    value = title,
                    onValueChange = {
                        title = it
                        if (showValidationErrors) showValidationErrors = false
                    },
                    singleLine = true,
                    isError = showValidationErrors && title.isBlank(),
                    modifier = Modifier.fillMaxWidth()
                )
                LabeledTextField(
                    label = stringResource(Res.string.memory_editor_body),
                    value = body,
                    onValueChange = {
                        body = it
                        if (showValidationErrors) showValidationErrors = false
                    },
                    singleLine = false,
                    isError = showValidationErrors && body.isBlank(),
                    height = 120.dp,
                    modifier = Modifier.fillMaxWidth()
                )
                MemoryMenuField(
                    label = stringResource(Res.string.memory_editor_kind),
                    selectedText = kind.label(),
                    modifier = Modifier.fillMaxWidth(),
                    options = MemoryFactKind.entries.map { entry ->
                        entry.label() to { kind = entry }
                    },
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { showAdvanced = !showAdvanced }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = if (showAdvanced) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = Color.White.copy(alpha = 0.7f),
                        )
                        Text(
                            text = stringResource(Res.string.memory_editor_advanced),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                    if (showAdvanced) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            LabeledTextField(
                                label = stringResource(Res.string.memory_editor_slot_key),
                                value = slotKey,
                                onValueChange = { slotKey = it },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(
                                modifier = Modifier
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { pinned = !pinned }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Checkbox(
                                    checked = pinned,
                                    onCheckedChange = { pinned = it },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = MaterialTheme.colorScheme.primary,
                                        uncheckedColor = Color.White.copy(alpha = 0.4f),
                                    ),
                                )
                                Text(
                                    text = stringResource(Res.string.memory_editor_pinned),
                                    color = Color.White.copy(alpha = 0.85f),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
                if (showValidationErrors) {
                    validationMessage?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }
            }
        },
    )
}


@Composable
private fun MemoryConfirmDialog(
    action: MemoryConfirmAction,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title = when (action) {
        is MemoryConfirmAction.Delete -> stringResource(Res.string.memory_confirm_delete_title)
        is MemoryConfirmAction.Retire -> stringResource(Res.string.memory_confirm_retire_title)
    }
    val message = when (action) {
        is MemoryConfirmAction.Delete -> stringResource(Res.string.memory_confirm_delete_message)
        is MemoryConfirmAction.Retire -> stringResource(Res.string.memory_confirm_retire_message)
    }.format(action.factTitle)

    ConfirmDialog(
        type = ConfirmDialogType.WARNING,
        title = title,
        message = message,
        confirmText = when (action) {
            is MemoryConfirmAction.Delete -> stringResource(Res.string.memory_action_delete)
            is MemoryConfirmAction.Retire -> stringResource(Res.string.memory_action_retire)
        },
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

@Composable
private fun MemoryInlineError(
    message: String,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = onDismiss) {
            Text(
                text = stringResource(Res.string.memory_error_inline_dismiss),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun MemoryDetailItem(
    label: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.55f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.glassColors.textPrimary,
        )
    }
}

@Composable
private fun MemoryBadge(
    text: String,
    tint: Color,
) {
    Box(
        modifier = Modifier
            .background(tint.copy(alpha = 0.16f), RoundedCornerShape(999.dp))
            .border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            color = tint,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun MemoryActionButton(
    text: String,
    tint: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick) {
        Text(text = text, color = tint)
    }
}

@Composable
private fun MemoryCenteredText(
    text: String,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.62f),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun MemoryMenuField(
    label: String,
    selectedText: String,
    options: List<Pair<String, () -> Unit>>,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(label, selectedText, options.size) { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (label.isNotBlank()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = Color.White.copy(alpha = 0.7f)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(Color(0x0DFFFFFF), shape)
                    .border(1.dp, Color(0x14FFFFFF), shape)
                    .clickable { expanded = true }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = selectedText,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            color = Color.White.copy(alpha = 0.9f)
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = Color.White.copy(alpha = 0.6f),
                    )
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .background(Color(0xFF1A1A1D), RoundedCornerShape(12.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp)),
            ) {
                options.forEach { (title, action) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = title,
                                color = MaterialTheme.glassColors.textPrimary,
                            )
                        },
                        onClick = {
                            expanded = false
                            action()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun memoryTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = MaterialTheme.glassColors.textPrimary,
    unfocusedTextColor = MaterialTheme.glassColors.textPrimary,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.35f),
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    unfocusedLabelColor = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.62f),
    cursorColor = MaterialTheme.colorScheme.primary,
)

private fun Modifier.panelSurface(): Modifier =
    clip(RoundedCornerShape(18.dp))
        .background(Color.Black.copy(alpha = 0.32f))
        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))

@Composable
private fun memoryScopeLabel(
    scopeType: String,
    scopeId: String,
): String {
    val cleanType = scopeType.trim()
    val cleanId = scopeId.trim()
    return when {
        cleanType.isBlank() && cleanId.isBlank() -> ""
        isGlobalScope(cleanType, cleanId) -> stringResource(Res.string.memory_scope_global)
        cleanType.equals("chat", ignoreCase = true) -> stringResource(Res.string.memory_scope_chat_format).format(cleanId)
        else -> "$cleanType:$cleanId"
    }
}

private fun isGlobalScope(
    scopeType: String,
    scopeId: String,
): Boolean = scopeType.trim().equals(DEFAULT_SCOPE_TYPE, ignoreCase = true) &&
    scopeId.trim().equals(DEFAULT_SCOPE_ID, ignoreCase = true)

@Composable
private fun MemoryStatusFilter.label(): String = when (this) {
    MemoryStatusFilter.ACTIVE -> stringResource(Res.string.memory_filter_status_active)
    MemoryStatusFilter.RETIRED -> stringResource(Res.string.memory_filter_status_retired)
    MemoryStatusFilter.DELETED -> stringResource(Res.string.memory_filter_status_deleted)
    MemoryStatusFilter.ALL -> stringResource(Res.string.memory_filter_status_all)
}

@Composable
private fun MemoryFactStatus.label(): String = when (this) {
    MemoryFactStatus.ACTIVE -> stringResource(Res.string.memory_filter_status_active)
    MemoryFactStatus.RETIRED -> stringResource(Res.string.memory_filter_status_retired)
    MemoryFactStatus.DELETED -> stringResource(Res.string.memory_filter_status_deleted)
}

@Composable
private fun MemoryFactKind.label(): String = when (this) {
    MemoryFactKind.SEMANTIC -> stringResource(Res.string.memory_kind_semantic)
    MemoryFactKind.PREFERENCE -> stringResource(Res.string.memory_kind_preference)
    MemoryFactKind.PROCEDURE -> stringResource(Res.string.memory_kind_procedure)
    MemoryFactKind.PROJECT_RULE -> stringResource(Res.string.memory_kind_project_rule)
    MemoryFactKind.EPISODE_NOTE -> stringResource(Res.string.memory_kind_episode_note)
    MemoryFactKind.PROJECT_DECISION -> stringResource(Res.string.memory_kind_project_decision)
}

@Composable
private fun createdByLabel(label: String): String = when (label) {
    "Manual" -> stringResource(Res.string.memory_created_by_manual)
    "Auto" -> stringResource(Res.string.memory_created_by_auto)
    "System" -> stringResource(Res.string.memory_created_by_system)
    else -> label
}

private const val DEFAULT_SCOPE_TYPE = "global"
private const val DEFAULT_SCOPE_ID = "global"
