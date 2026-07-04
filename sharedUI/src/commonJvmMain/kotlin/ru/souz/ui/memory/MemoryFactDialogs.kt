package ru.souz.ui.memory

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import org.jetbrains.compose.resources.stringResource
import ru.souz.memory.MemoryEvidenceDetail
import ru.souz.memory.MemoryFactKind
import ru.souz.memory.MemoryFactStatus
import souz.sharedui.generated.resources.Res
import souz.sharedui.generated.resources.button_close
import souz.sharedui.generated.resources.dialog_cancel
import souz.sharedui.generated.resources.memory_action_delete
import souz.sharedui.generated.resources.memory_action_edit
import souz.sharedui.generated.resources.memory_action_pin
import souz.sharedui.generated.resources.memory_action_retire
import souz.sharedui.generated.resources.memory_action_unpin
import souz.sharedui.generated.resources.memory_badge_pinned
import souz.sharedui.generated.resources.memory_button_save
import souz.sharedui.generated.resources.memory_button_saving
import souz.sharedui.generated.resources.memory_confirm_delete_message
import souz.sharedui.generated.resources.memory_confirm_delete_title
import souz.sharedui.generated.resources.memory_confirm_retire_message
import souz.sharedui.generated.resources.memory_confirm_retire_title
import souz.sharedui.generated.resources.memory_details_confidence
import souz.sharedui.generated.resources.memory_details_evidence
import souz.sharedui.generated.resources.memory_details_loading
import souz.sharedui.generated.resources.memory_details_scope
import souz.sharedui.generated.resources.memory_details_select
import souz.sharedui.generated.resources.memory_details_status
import souz.sharedui.generated.resources.memory_details_title
import souz.sharedui.generated.resources.memory_details_updated_at
import souz.sharedui.generated.resources.memory_editor_body
import souz.sharedui.generated.resources.memory_editor_create_title
import souz.sharedui.generated.resources.memory_editor_edit_title
import souz.sharedui.generated.resources.memory_editor_error_body_required
import souz.sharedui.generated.resources.memory_editor_error_title_required
import souz.sharedui.generated.resources.memory_editor_kind
import souz.sharedui.generated.resources.memory_editor_title
import souz.sharedui.generated.resources.memory_evidence_empty

@Composable
internal fun MemoryFactDetailsPanel(
    state: MemoryUiState,
    onAction: (MemoryAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(topEnd = 18.dp, bottomEnd = 18.dp))
            .background(MemoryUiColors.Panel)
            .border(1.dp, MemoryUiColors.TextPrimary.copy(alpha = 0.08f), RoundedCornerShape(topEnd = 18.dp, bottomEnd = 18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.memory_details_title).uppercase(),
                color = MemoryUiColors.TextPrimary.copy(alpha = 0.4f),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
            )
            IconButton(onClick = { onAction(MemoryAction.CloseDetails) }, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(Res.string.button_close),
                    tint = MemoryUiColors.TextPrimary.copy(alpha = 0.45f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        when {
            state.isDetailsLoading -> MemoryCenteredText(stringResource(Res.string.memory_details_loading))
            state.selectedFact == null -> MemoryCenteredText(stringResource(Res.string.memory_details_select))
            else -> MemoryFactDetailsContent(
                state = state,
                onAction = onAction,
            )
        }
    }
}

@Composable
private fun MemoryFactDetailsContent(
    state: MemoryUiState,
    onAction: (MemoryAction) -> Unit,
) {
    val details = state.selectedFact ?: return
    val fact = details.fact

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = fact.title,
                color = MemoryUiColors.TextPrimary.copy(alpha = 0.9f),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 19.sp,
            )

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KindBadge(fact.kind)
                if (fact.pinned) {
                    MemorySmallBadge(
                        text = stringResource(Res.string.memory_badge_pinned),
                        color = MemoryUiColors.Accent,
                    )
                }
                MemorySmallBadge(
                    text = createdByLabel(fact.createdBy),
                    color = MemoryUiColors.TextPrimary.copy(alpha = 0.4f),
                )
            }

            Text(
                text = fact.body,
                color = MemoryUiColors.TextPrimary.copy(alpha = 0.6f),
                fontSize = 12.5.sp,
                lineHeight = 19.sp,
            )

            MetaGrid(fact)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.memory_details_confidence),
                    color = MemoryUiColors.TextPrimary.copy(alpha = 0.35f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                ConfidenceBar(confidence = fact.confidence, width = 150.dp, height = 6.dp)
            }

            EvidenceSection(details.evidence)
        }

        HorizontalDivider(color = MemoryUiColors.Divider)

        DetailActionButton(
            text = stringResource(Res.string.memory_action_edit),
            onClick = { onAction(MemoryAction.OpenEditDialog(fact.id)) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DetailActionButton(
                text = if (fact.pinned) {
                    stringResource(Res.string.memory_action_unpin)
                } else {
                    stringResource(Res.string.memory_action_pin)
                },
                accent = fact.pinned,
                modifier = Modifier.weight(1f),
                onClick = { onAction(MemoryAction.SetPinned(fact.id, !fact.pinned)) },
            )
            if (fact.status == MemoryFactStatus.ACTIVE) {
                DetailActionButton(
                    text = stringResource(Res.string.memory_action_retire),
                    modifier = Modifier.weight(1f),
                    onClick = { onAction(MemoryAction.AskRetire(fact.id)) },
                )
            }
        }
    }
}

@Composable
private fun MetaGrid(fact: ru.souz.memory.MemoryFact) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetaCell(
                label = stringResource(Res.string.memory_editor_kind),
                value = fact.kind.label(),
                modifier = Modifier.weight(1f),
            )
            MetaCell(
                label = stringResource(Res.string.memory_details_status),
                value = fact.status.label(),
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetaCell(
                label = stringResource(Res.string.memory_details_scope),
                value = fact.scope.memoryLabel(),
                modifier = Modifier.weight(1f),
            )
            MetaCell(
                label = stringResource(Res.string.memory_details_updated_at),
                value = fact.updatedAt.shortMemoryLabel(),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MetaCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = label.uppercase(),
            color = MemoryUiColors.TextPrimary.copy(alpha = 0.25f),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            color = MemoryUiColors.TextPrimary.copy(alpha = 0.74f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EvidenceSection(evidence: List<MemoryEvidenceDetail>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(Res.string.memory_details_evidence).uppercase(),
            color = MemoryUiColors.TextPrimary.copy(alpha = 0.4f),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (evidence.isEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MemoryUiColors.TextPrimary.copy(alpha = 0.04f))
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircleOutline,
                    contentDescription = null,
                    tint = MemoryUiColors.TextPrimary.copy(alpha = 0.35f),
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = stringResource(Res.string.memory_evidence_empty),
                    color = MemoryUiColors.TextPrimary.copy(alpha = 0.4f),
                    fontSize = 12.sp,
                )
            }
        } else {
            evidence.forEach { MemoryEvidenceCard(it) }
        }
    }
}

@Composable
private fun MemoryEvidenceCard(evidence: MemoryEvidenceDetail) {
    val evidenceText = evidence.displayText()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MemoryUiColors.TextPrimary.copy(alpha = 0.04f))
            .border(1.dp, MemoryUiColors.TextPrimary.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = evidenceText,
            color = MemoryUiColors.TextPrimary.copy(alpha = 0.62f),
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
        Text(
            text = listOfNotNull(
                evidence.sourceEvent.sourceType,
                evidence.sourceEvent.sourceRef,
                evidence.sourceEvent.createdAt.shortMemoryLabel(),
            ).joinToString(" · "),
            color = MemoryUiColors.TextPrimary.copy(alpha = 0.32f),
            fontSize = 10.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MemorySmallBadge(
    text: String,
    color: Color,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 7.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun DetailActionButton(
    text: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(36.dp),
        color = if (accent) MemoryUiColors.Accent.copy(alpha = 0.12f) else MemoryUiColors.TextPrimary.copy(alpha = 0.07f),
        contentColor = if (accent) MemoryUiColors.Accent else MemoryUiColors.TextPrimary.copy(alpha = 0.76f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text = text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
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
    val canonicalKey = editor.input.canonicalKey
    var pinned by remember(editor) { mutableStateOf(editor.input.pinned) }
    var showValidationErrors by remember { mutableStateOf(false) }

    val validationMessage = when {
        title.isBlank() -> stringResource(Res.string.memory_editor_error_title_required)
        body.isBlank() -> stringResource(Res.string.memory_editor_error_body_required)
        else -> null
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.width(520.dp),
            color = MemoryUiColors.Panel,
            contentColor = MemoryUiColors.TextPrimary,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MemoryUiColors.TextPrimary.copy(alpha = 0.1f)),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (editor.mode == MemoryEditorMode.CREATE) {
                            stringResource(Res.string.memory_editor_create_title)
                        } else {
                            stringResource(Res.string.memory_editor_edit_title)
                        },
                        color = MemoryUiColors.TextPrimary.copy(alpha = 0.9f),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(30.dp)) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(Res.string.button_close),
                            tint = MemoryUiColors.TextPrimary.copy(alpha = 0.45f),
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = {
                        title = it
                        if (showValidationErrors) showValidationErrors = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(Res.string.memory_editor_title)) },
                    isError = showValidationErrors && title.isBlank(),
                    shape = RoundedCornerShape(10.dp),
                    colors = memoryTextFieldColors(),
                )

                OutlinedTextField(
                    value = body,
                    onValueChange = {
                        body = it
                        if (showValidationErrors) showValidationErrors = false
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 118.dp),
                    minLines = 4,
                    label = { Text(stringResource(Res.string.memory_editor_body)) },
                    isError = showValidationErrors && body.isBlank(),
                    shape = RoundedCornerShape(10.dp),
                    colors = memoryTextFieldColors(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MemoryMenuField(
                        selectedText = kind.label(),
                        selectedColor = kind.kindStyle().color,
                        modifier = Modifier.weight(1f),
                        options = MemoryFactKind.entries.map { entry ->
                            MemoryMenuOption(entry.label(), entry.kindStyle().color) { kind = entry }
                        },
                    )
                    Surface(
                        onClick = { pinned = !pinned },
                        modifier = Modifier.size(width = 40.dp, height = 34.dp),
                        color = if (pinned) MemoryUiColors.Accent.copy(alpha = 0.12f) else MemoryUiColors.TextPrimary.copy(alpha = 0.05f),
                        contentColor = if (pinned) MemoryUiColors.Accent else MemoryUiColors.TextPrimary.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, if (pinned) MemoryUiColors.Accent.copy(alpha = 0.25f) else MemoryUiColors.TextPrimary.copy(alpha = 0.07f)),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.PushPin, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                }

                if (showValidationErrors && validationMessage != null) {
                    Text(
                        text = validationMessage,
                        color = MemoryUiColors.Danger,
                        fontSize = 12.sp,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = stringResource(Res.string.dialog_cancel),
                            color = MemoryUiColors.TextPrimary.copy(alpha = 0.65f),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Button(
                        onClick = {
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
                                        canonicalKey = canonicalKey,
                                        pinned = pinned,
                                    )
                                )
                            }
                        },
                        enabled = !isSaving && title.isNotBlank() && body.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MemoryUiColors.Accent,
                            contentColor = MemoryUiColors.Screen,
                            disabledContainerColor = MemoryUiColors.TextPrimary.copy(alpha = 0.08f),
                            disabledContentColor = MemoryUiColors.TextPrimary.copy(alpha = 0.3f),
                        ),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            text = if (isSaving) {
                                stringResource(Res.string.memory_button_saving)
                            } else {
                                stringResource(Res.string.memory_button_save)
                            },
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
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

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.width(420.dp),
            color = MemoryUiColors.Panel,
            contentColor = MemoryUiColors.TextPrimary,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MemoryUiColors.TextPrimary.copy(alpha = 0.1f)),
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.WarningAmber,
                        contentDescription = null,
                        tint = MemoryUiColors.Danger,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = title,
                        color = MemoryUiColors.TextPrimary.copy(alpha = 0.9f),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    text = message,
                    color = MemoryUiColors.TextPrimary.copy(alpha = 0.62f),
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = stringResource(Res.string.dialog_cancel),
                            color = MemoryUiColors.TextPrimary.copy(alpha = 0.65f),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = onConfirm,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MemoryUiColors.Danger,
                            contentColor = MemoryUiColors.Screen,
                        ),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            text = when (action.kind) {
                                PendingMemoryConfirm.Kind.Delete -> stringResource(Res.string.memory_action_delete)
                                PendingMemoryConfirm.Kind.Retire -> stringResource(Res.string.memory_action_retire)
                            },
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}
