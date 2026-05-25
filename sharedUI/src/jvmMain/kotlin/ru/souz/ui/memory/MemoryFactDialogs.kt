package ru.souz.ui.memory

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.stringResource
import ru.souz.memory.MemoryEvidenceDetail
import ru.souz.memory.MemoryFactKind
import ru.souz.memory.MemoryFactStatus
import ru.souz.ui.common.ConfirmDialog
import ru.souz.ui.common.ConfirmDialogType
import ru.souz.ui.components.LabeledTextField
import ru.souz.ui.glassColors
import souz.sharedui.generated.resources.*

@Composable
internal fun MemoryFactDetailsPanel(
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

            else -> state.selectedFact?.let { details ->
                val fact = details.fact
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
                        MemoryBadge(text = createdByLabel(fact.createdBy), tint = Color(0xFF82B1FF))
                        if (fact.status != MemoryFactStatus.ACTIVE) {
                            MemoryBadge(text = fact.status.label(), tint = Color(0xFFFFB86C))
                        }
                    }

                    Text(
                        text = fact.body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.88f),
                    )

                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                    MemoryDetailItem(stringResource(Res.string.memory_editor_kind), fact.kind.label())
                    MemoryDetailItem(stringResource(Res.string.memory_details_scope), fact.scope.memoryLabel())
                    MemoryDetailItem(stringResource(Res.string.memory_details_status), fact.status.label())
                    MemoryDetailItem(stringResource(Res.string.memory_details_confidence), fact.confidence.confidenceLabel())
                    MemoryDetailItem(stringResource(Res.string.memory_details_created_by), createdByLabel(fact.createdBy))
                    MemoryDetailItem(stringResource(Res.string.memory_details_created_at), fact.createdAt.memoryLabel())
                    MemoryDetailItem(stringResource(Res.string.memory_details_updated_at), fact.updatedAt.memoryLabel())
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

                    if (details.evidence.isEmpty()) {
                        Text(
                            text = stringResource(Res.string.memory_evidence_empty),
                            color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        details.evidence.forEach { evidence ->
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
                        if (fact.status != MemoryFactStatus.RETIRED && fact.status != MemoryFactStatus.DELETED) {
                            MemoryActionButton(stringResource(Res.string.memory_action_retire)) {
                                onAction(MemoryAction.AskRetire(fact.id))
                            }
                        }
                        if (fact.status != MemoryFactStatus.DELETED) {
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
    evidence: MemoryEvidenceDetail,
) {
    val evidenceText = evidence.displayText()
    val sourceText = evidence.sourceEvent.text.trim()
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
            text = evidenceText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.glassColors.textPrimary,
        )
        if (sourceText != evidenceText) {
            Text(
                text = sourceText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.68f),
                lineHeight = 18.sp,
            )
        }
        Text(
            text = listOfNotNull(
                evidence.sourceEvent.sourceType,
                evidence.sourceEvent.sourceRef,
                evidence.sourceEvent.createdAt.memoryLabel(),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.55f),
        )
    }
}

@Composable
internal fun MemoryFactEditorDialog(
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
                LabeledTextField(
                    label = stringResource(Res.string.memory_editor_slot_key),
                    value = slotKey,
                    onValueChange = { slotKey = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                        modifier = Modifier.clickable { pinned = !pinned },
                    )
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
internal fun MemoryConfirmDialog(
    action: PendingMemoryConfirm,
    factTitle: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title = when (action.kind) {
        PendingMemoryConfirm.Kind.Delete -> stringResource(Res.string.memory_confirm_delete_title)
        PendingMemoryConfirm.Kind.Retire -> stringResource(Res.string.memory_confirm_retire_title)
    }
    val message = when (action.kind) {
        PendingMemoryConfirm.Kind.Delete -> stringResource(Res.string.memory_confirm_delete_message)
        PendingMemoryConfirm.Kind.Retire -> stringResource(Res.string.memory_confirm_retire_message)
    }.format(factTitle)

    ConfirmDialog(
        type = ConfirmDialogType.WARNING,
        title = title,
        message = message,
        confirmText = when (action.kind) {
            PendingMemoryConfirm.Kind.Delete -> stringResource(Res.string.memory_action_delete)
            PendingMemoryConfirm.Kind.Retire -> stringResource(Res.string.memory_action_retire)
        },
        onConfirm = onConfirm,
        onDismiss = onDismiss,
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
internal fun MemoryMenuField(
    label: String,
    selectedText: String,
    options: List<Pair<String, () -> Unit>>,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(label, selectedText, options.size) { mutableStateOf(false) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label.isNotBlank()) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
        }
        Box(modifier = Modifier.fillMaxWidth()) {
            TextButton(
                onClick = { expanded = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp),
            ) {
                Text(
                    text = selectedText,
                    modifier = Modifier.weight(1f),
                    color = Color.White.copy(alpha = 0.9f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = Color.White.copy(alpha = 0.6f),
                )
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
                        text = { Text(title, color = MaterialTheme.glassColors.textPrimary) },
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

private fun Modifier.panelSurface(): Modifier =
    clip(RoundedCornerShape(18.dp))
        .background(Color.Black.copy(alpha = 0.32f))
        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
