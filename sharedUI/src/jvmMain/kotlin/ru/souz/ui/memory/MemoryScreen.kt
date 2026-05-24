package ru.souz.ui.memory

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.Color
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
import ru.souz.ui.common.ConfirmDialog
import ru.souz.ui.common.ConfirmDialogType
import ru.souz.ui.common.DraggableWindowArea
import ru.souz.ui.glassColors
import ru.souz.ui.main.RealLiquidGlassCard
import souz.sharedui.generated.resources.Res
import souz.sharedui.generated.resources.button_close
import souz.sharedui.generated.resources.memory_action_delete
import souz.sharedui.generated.resources.memory_action_details
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
import souz.sharedui.generated.resources.memory_section_other
import souz.sharedui.generated.resources.memory_section_pinned
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
                            Button(onClick = { onAction(MemoryAction.OpenCreateDialog) }) {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(Res.string.memory_create))
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

@Composable
private fun MemoryFilters(
    filters: MemoryFiltersUi,
    onFiltersChange: (MemoryFiltersUi) -> Unit,
) {
    val allScopesLabel = stringResource(Res.string.memory_scope_all)
    val globalScopeLabel = stringResource(Res.string.memory_scope_global)
    val selectedScopeLabel = remember(filters.scopeType, filters.scopeId, allScopesLabel, globalScopeLabel) {
        when {
            filters.scopeType.isBlank() && filters.scopeId.isBlank() -> allScopesLabel
            isGlobalScope(filters.scopeType, filters.scopeId) -> globalScopeLabel
            else -> memoryScopeLabel(filters.scopeType, filters.scopeId)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = filters.query,
                onValueChange = { onFiltersChange(filters.copy(query = it)) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text(stringResource(Res.string.memory_filter_query)) },
                colors = memoryTextFieldColors(),
            )

            MemoryMenuField(
                label = stringResource(Res.string.memory_filter_status),
                selectedText = filters.status.label(),
                modifier = Modifier.width(170.dp),
                options = MemoryStatusFilter.entries.map { status ->
                    status.label() to { onFiltersChange(filters.copy(status = status)) }
                },
            )

            MemoryMenuField(
                label = stringResource(Res.string.memory_filter_kind),
                selectedText = filters.kind?.let(::kindLabel) ?: stringResource(Res.string.memory_filter_kind_all),
                modifier = Modifier.width(190.dp),
                options = listOf(
                    stringResource(Res.string.memory_filter_kind_all) to {
                        onFiltersChange(filters.copy(kind = null))
                    }
                ) + MemoryFactKind.entries.map { kind ->
                    kindLabel(kind) to { onFiltersChange(filters.copy(kind = kind)) }
                },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MemoryMenuField(
                label = stringResource(Res.string.memory_filter_scope),
                selectedText = selectedScopeLabel,
                modifier = Modifier.width(220.dp),
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
}

@Composable
private fun MemoryFactsContent(
    state: MemoryUiState,
    onAction: (MemoryAction) -> Unit,
) {
    val pinnedFacts = remember(state.facts) { state.facts.filter { it.pinned } }
    val otherFacts = remember(state.facts) { state.facts.filterNot { it.pinned } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .panelSurface(),
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
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (pinnedFacts.isNotEmpty()) {
                        item {
                            MemorySectionHeader(stringResource(Res.string.memory_section_pinned))
                        }
                        items(pinnedFacts, key = MemoryFactUi::id) { fact ->
                            MemoryFactRow(fact = fact, onAction = onAction)
                        }
                    }

                    if (otherFacts.isNotEmpty()) {
                        item {
                            MemorySectionHeader(
                                if (pinnedFacts.isNotEmpty()) {
                                    stringResource(Res.string.memory_section_other)
                                } else {
                                    stringResource(Res.string.memory_title)
                                }
                            )
                        }
                        items(otherFacts, key = MemoryFactUi::id) { fact ->
                            MemoryFactRow(fact = fact, onAction = onAction)
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
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            .clickable { onAction(MemoryAction.OpenDetails(fact.id)) }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = fact.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.glassColors.textPrimary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (fact.pinned) {
                        MemoryBadge(text = "Pinned", tint = MaterialTheme.colorScheme.primary)
                    }
                    MemoryBadge(text = fact.createdByLabel, tint = Color(0xFF82B1FF))
                    if (fact.status != "Active") {
                        MemoryBadge(text = fact.status, tint = Color(0xFFFFB86C))
                    }
                }
            }

            Text(
                text = fact.updatedAtLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.55f),
            )
        }

        Text(
            text = fact.bodyPreview,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.82f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )

        Text(
            text = "${fact.kind} · ${fact.scopeLabel} · ${fact.confidenceLabel}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.58f),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MemoryActionButton(stringResource(Res.string.memory_action_details)) {
                onAction(MemoryAction.OpenDetails(fact.id))
            }
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
            if (fact.status != "Retired" && fact.status != "Deleted") {
                MemoryActionButton(stringResource(Res.string.memory_action_retire)) {
                    onAction(MemoryAction.AskRetire(fact.id))
                }
            }
            if (fact.status != "Deleted") {
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
                            MemoryBadge(text = "Pinned", tint = MaterialTheme.colorScheme.primary)
                        }
                        MemoryBadge(text = fact.createdByLabel, tint = Color(0xFF82B1FF))
                        if (fact.status != "Active") {
                            MemoryBadge(text = fact.status, tint = Color(0xFFFFB86C))
                        }
                    }

                    Text(
                        text = fact.body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.88f),
                    )

                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                    MemoryDetailItem(stringResource(Res.string.memory_editor_kind), fact.kind)
                    MemoryDetailItem(stringResource(Res.string.memory_details_scope), fact.scopeLabel)
                    MemoryDetailItem(stringResource(Res.string.memory_details_status), fact.status)
                    MemoryDetailItem("Confidence", fact.confidenceLabel)
                    MemoryDetailItem(stringResource(Res.string.memory_details_created_by), fact.createdByLabel)
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
                        if (fact.status != "Retired" && fact.status != "Deleted") {
                            MemoryActionButton(stringResource(Res.string.memory_action_retire)) {
                                onAction(MemoryAction.AskRetire(fact.id))
                            }
                        }
                        if (fact.status != "Deleted") {
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

    val validationMessage = remember(title, body) {
        when {
            title.isBlank() -> "Title is required"
            body.isBlank() -> "Body is required"
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
        dialogMaxWidth = 720.dp,
        confirmText = if (isSaving) {
            stringResource(Res.string.memory_button_saving)
        } else {
            stringResource(Res.string.memory_button_save)
        },
        confirmEnabled = !isSaving && validationMessage == null,
        onConfirm = {
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
        },
        onDismiss = onDismiss,
        detailsContent = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(Res.string.memory_editor_title)) },
                    colors = memoryTextFieldColors(),
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    label = { Text(stringResource(Res.string.memory_editor_body)) },
                    colors = memoryTextFieldColors(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MemoryMenuField(
                        label = stringResource(Res.string.memory_editor_kind),
                        selectedText = kindLabel(kind),
                        modifier = Modifier.weight(1f),
                        options = MemoryFactKind.entries.map { entry ->
                            kindLabel(entry) to { kind = entry }
                        },
                    )
                    OutlinedTextField(
                        value = slotKey,
                        onValueChange = { slotKey = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text(stringResource(Res.string.memory_editor_slot_key)) },
                        colors = memoryTextFieldColors(),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "${stringResource(Res.string.memory_details_scope)}: $scopeLabel",
                        color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(
                        checked = pinned,
                        onCheckedChange = { pinned = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary,
                            uncheckedColor = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.65f),
                        ),
                    )
                    Text(
                        text = stringResource(Res.string.memory_editor_pinned),
                        color = MaterialTheme.glassColors.textPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                validationMessage?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
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
private fun MemorySectionHeader(
    title: String,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.7f),
    )
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
    val shape = RoundedCornerShape(4.dp)

    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clip(shape)
                .border(1.dp, MaterialTheme.glassColors.textPrimary.copy(alpha = 0.35f), shape)
                .clickable { expanded = true }
                .padding(horizontal = 16.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.62f),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = selectedText,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.glassColors.textPrimary,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.75f),
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1A1D), RoundedCornerShape(12.dp)),
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

private fun memoryScopeLabel(
    scopeType: String,
    scopeId: String,
): String {
    val cleanType = scopeType.trim()
    val cleanId = scopeId.trim()
    return when {
        cleanType.isBlank() && cleanId.isBlank() -> ""
        isGlobalScope(cleanType, cleanId) -> "Global"
        cleanType.equals("chat", ignoreCase = true) -> "Chat: $cleanId"
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

private fun kindLabel(kind: MemoryFactKind): String = kind.name.lowercase()
    .split('_')
    .joinToString(" ") { token -> token.replaceFirstChar(Char::titlecase) }

private const val DEFAULT_SCOPE_TYPE = "global"
private const val DEFAULT_SCOPE_ID = "global"
