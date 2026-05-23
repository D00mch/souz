package ru.souz.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.focusable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.collectLatest
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.compose.localDI
import ru.souz.agent.memory.MemoryFactRecord
import ru.souz.ui.common.DraggableWindowArea
import ru.souz.ui.glassColors
import ru.souz.ui.main.RealLiquidGlassCard
import souz.sharedui.generated.resources.Res
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import souz.sharedui.generated.resources.memory_inspector_auto_write
import souz.sharedui.generated.resources.memory_inspector_action_copy_evidence
import souz.sharedui.generated.resources.memory_inspector_action_forget
import souz.sharedui.generated.resources.memory_inspector_action_invalidate
import souz.sharedui.generated.resources.memory_inspector_action_open_source_ref
import souz.sharedui.generated.resources.memory_inspector_action_rebuild_embeddings
import souz.sharedui.generated.resources.memory_inspector_action_run_consolidation
import souz.sharedui.generated.resources.memory_inspector_diagnostics
import souz.sharedui.generated.resources.memory_inspector_empty
import souz.sharedui.generated.resources.memory_inspector_evidence
import souz.sharedui.generated.resources.memory_inspector_fact_docs
import souz.sharedui.generated.resources.memory_inspector_fact_label
import souz.sharedui.generated.resources.memory_inspector_graph
import souz.sharedui.generated.resources.memory_inspector_injection_count
import souz.sharedui.generated.resources.memory_inspector_last_injection
import souz.sharedui.generated.resources.memory_inspector_last_write
import souz.sharedui.generated.resources.memory_inspector_manual_actions
import souz.sharedui.generated.resources.memory_inspector_no_diagnostics
import souz.sharedui.generated.resources.memory_inspector_no_evidence
import souz.sharedui.generated.resources.memory_inspector_no_injections
import souz.sharedui.generated.resources.memory_inspector_no_rejections
import souz.sharedui.generated.resources.memory_inspector_no_timeline
import souz.sharedui.generated.resources.memory_inspector_overview
import souz.sharedui.generated.resources.memory_inspector_profile_docs
import souz.sharedui.generated.resources.memory_inspector_recent_injections
import souz.sharedui.generated.resources.memory_inspector_refresh
import souz.sharedui.generated.resources.memory_inspector_rejected_writes
import souz.sharedui.generated.resources.memory_inspector_rejection_reasons
import souz.sharedui.generated.resources.memory_inspector_disabled_by_setting
import souz.sharedui.generated.resources.memory_inspector_estimated_tokens
import souz.sharedui.generated.resources.memory_inspector_snack_copied
import souz.sharedui.generated.resources.memory_inspector_subtitle
import souz.sharedui.generated.resources.memory_inspector_timeline
import souz.sharedui.generated.resources.memory_inspector_title
import souz.sharedui.generated.resources.memory_inspector_episode_docs

private val MemoryCardBackground = Color.White.copy(alpha = 0.05f)
private val MemoryCardBackgroundSelected = Color.White.copy(alpha = 0.10f)
private val MemoryCardBorder = Color.White.copy(alpha = 0.08f)
private val MemoryCardBorderStrong = Color.White.copy(alpha = 0.12f)
private val MemoryStrongText @Composable get() = MaterialTheme.glassColors.textPrimary
private val MemoryMutedText = Color.White.copy(alpha = 0.62f)
private val MemoryAccent = Color(0xFF82B1FF)
private val MemoryShellDivider = Color.White.copy(alpha = 0.08f)

@Composable
fun MemoryInspectorScreen(
    onClose: () -> Unit,
    onShowSnack: (String) -> Unit,
) {
    val di = localDI()
    val viewModel = viewModel { MemoryInspectorViewModel(di) }
    val state by viewModel.uiState.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val windowInfo = LocalWindowInfo.current
    val copiedMessage = stringResource(Res.string.memory_inspector_snack_copied)

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is MemoryInspectorEffect.CopyToClipboard -> {
                    clipboardManager.setText(AnnotatedString(effect.text))
                    onShowSnack(copiedMessage)
                }
                is MemoryInspectorEffect.ShowSnackbar -> onShowSnack(effect.message)
            }
        }
    }

    MemoryInspectorContent(
        state = state,
        isWindowFocused = windowInfo.isWindowFocused,
        onRefresh = { viewModel.send(MemoryInspectorEvent.Refresh) },
        onSelectFact = { viewModel.send(MemoryInspectorEvent.SelectFact(it)) },
        onSelectEvidence = { viewModel.send(MemoryInspectorEvent.SelectEvidence(it)) },
        onForgetFact = { viewModel.send(MemoryInspectorEvent.ForgetSelectedFact) },
        onInvalidateFact = { viewModel.send(MemoryInspectorEvent.InvalidateSelectedFact) },
        onRebuildEmbeddings = { viewModel.send(MemoryInspectorEvent.RebuildEmbeddings) },
        onRunConsolidation = { viewModel.send(MemoryInspectorEvent.RunConsolidation) },
        onCopyEvidence = { viewModel.send(MemoryInspectorEvent.CopySelectedEvidence) },
        onOpenSourceRef = { viewModel.send(MemoryInspectorEvent.OpenSelectedSourceRef) },
        onToggleAutoWrite = { viewModel.send(MemoryInspectorEvent.ToggleAutoWrite(it)) },
        onClose = onClose,
    )
}

@Composable
private fun MemoryInspectorContent(
    state: MemoryInspectorState,
    isWindowFocused: Boolean,
    onRefresh: () -> Unit,
    onSelectFact: (String) -> Unit,
    onSelectEvidence: (Int) -> Unit,
    onForgetFact: () -> Unit,
    onInvalidateFact: () -> Unit,
    onRebuildEmbeddings: () -> Unit,
    onRunConsolidation: () -> Unit,
    onCopyEvidence: () -> Unit,
    onOpenSourceRef: () -> Unit,
    onToggleAutoWrite: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val entitiesById = remember(state.graphSnapshot.entities) {
        state.graphSnapshot.entities.associateBy { it.id }
    }
    val factsById = remember(state.graphSnapshot.facts) {
        state.graphSnapshot.facts.associateBy { it.id }
    }
    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    onClose()
                    true
                } else {
                    false
                }
            },
    ) {
        RealLiquidGlassCard(
            modifier = Modifier.fillMaxSize(),
            isWindowFocused = isWindowFocused,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 24.dp),
            ) {
                DraggableWindowArea {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(Res.string.memory_inspector_title),
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Bold,
                                color = MemoryStrongText,
                            )
                            Text(
                                text = stringResource(Res.string.memory_inspector_subtitle),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MemoryMutedText,
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedButton(
                                onClick = onRefresh,
                                enabled = !state.isLoading && !state.isWorking,
                                border = BorderStroke(1.dp, MemoryCardBorder),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = MemoryCardBackground,
                                    contentColor = MemoryStrongText,
                                ),
                            ) {
                                Text(stringResource(Res.string.memory_inspector_refresh))
                            }
                            IconButton(onClick = onClose) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = null,
                                    tint = MemoryStrongText.copy(alpha = 0.8f),
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (state.isLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MemoryAccent,
                            )
                        }
                    }

                    state.errorMessage?.let { error ->
                        MemoryInspectorCard(title = stringResource(Res.string.memory_inspector_diagnostics)) {
                            Text(
                                text = error,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }

                    MemoryInspectorCard(title = stringResource(Res.string.memory_inspector_overview)) {
                        MemoryOverviewRow(
                            label = stringResource(Res.string.memory_inspector_fact_label),
                            value = state.overview.activeFactCount.toString(),
                        )
                        MemoryOverviewRow(
                            label = stringResource(Res.string.memory_inspector_profile_docs),
                            value = state.overview.activeProfileDocCount.toString(),
                        )
                        MemoryOverviewRow(
                            label = stringResource(Res.string.memory_inspector_fact_docs),
                            value = state.overview.activeFactDocCount.toString(),
                        )
                        MemoryOverviewRow(
                            label = stringResource(Res.string.memory_inspector_episode_docs),
                            value = state.overview.activeEpisodeDocCount.toString(),
                        )
                    }

                    MemoryInspectorCard(title = stringResource(Res.string.memory_inspector_graph)) {
                        if (state.graphSnapshot.facts.isEmpty()) {
                            MemoryEmptyState(stringResource(Res.string.memory_inspector_empty))
                        } else {
                            state.graphSnapshot.facts
                                .groupBy { it.subjectEntityId }
                                .forEach { (subjectId, facts) ->
                                    Text(
                                        text = entitiesById[subjectId]?.displayName ?: subjectId,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MemoryStrongText,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    facts.forEach { fact ->
                                        val factId = fact.id ?: return@forEach
                                        MemoryFactRow(
                                            fact = fact,
                                            isSelected = state.selectedFactId == factId,
                                            objectLabel = fact.renderObjectLabel(entitiesById),
                                            onClick = { onSelectFact(factId) },
                                        )
                                    }
                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 8.dp),
                                        color = MemoryShellDivider,
                                    )
                                }
                        }
                    }

                    MemoryInspectorCard(title = stringResource(Res.string.memory_inspector_timeline)) {
                        if (state.timeline.isEmpty()) {
                            MemoryEmptyState(stringResource(Res.string.memory_inspector_no_timeline))
                        } else {
                            state.timeline.forEach { event ->
                                val label = factsById[event.factId]?.let { fact ->
                                    "${fact.predicate.replace('_', ' ')}: ${fact.renderObjectLabel(entitiesById)}"
                                }.orEmpty()
                                Text(
                                    text = "${event.type} • $label",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MemoryStrongText,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                )
                                event.relatedFactId?.let { relatedFactId ->
                                    factsById[relatedFactId]?.let { related ->
                                        Text(
                                            text = related.renderObjectLabel(entitiesById),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MemoryMutedText,
                                            modifier = Modifier.padding(bottom = 6.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    MemoryInspectorCard(title = stringResource(Res.string.memory_inspector_evidence)) {
                        if (state.evidence.isEmpty()) {
                            MemoryEmptyState(stringResource(Res.string.memory_inspector_no_evidence))
                        } else {
                            state.evidence.forEachIndexed { index, evidence ->
                                val isSelected = state.selectedEvidenceIndex == index
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            if (isSelected) MemoryCardBackgroundSelected else MemoryCardBackground,
                                            RoundedCornerShape(10.dp),
                                        )
                                        .border(
                                            1.dp,
                                            if (isSelected) MemoryCardBorderStrong else MemoryCardBorder,
                                            RoundedCornerShape(10.dp),
                                        )
                                        .clickable { onSelectEvidence(index) }
                                        .padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = evidence.evidenceType.name,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MemoryAccent,
                                    )
                                    Text(
                                        text = evidence.sourceRef,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MemoryMutedText,
                                    )
                                    evidence.contentExcerpt?.takeIf { it.isNotBlank() }?.let { excerpt ->
                                        Text(
                                            text = excerpt,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MemoryStrongText,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    MemoryInspectorCard(title = stringResource(Res.string.memory_inspector_diagnostics)) {
                        val lastWrite = state.diagnostics.lastWriteAttempt
                        val lastInjection = state.diagnostics.lastInjection
                        if (!state.diagnostics.automaticMemoryEnabled) {
                            Text(
                                text = stringResource(Res.string.memory_inspector_disabled_by_setting),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MemoryAccent,
                            )
                        }
                        if (lastWrite == null && lastInjection == null) {
                            MemoryEmptyState(stringResource(Res.string.memory_inspector_no_diagnostics))
                        } else {
                            lastWrite?.let { attempt ->
                                Text(
                                    text = stringResource(Res.string.memory_inspector_last_write),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MemoryStrongText,
                                )
                                Text(
                                    text = attempt.triggerType,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MemoryMutedText,
                                )
                                attempt.inputExcerpt?.let { excerpt ->
                                    Text(
                                        text = excerpt,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MemoryStrongText,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                                attempt.acceptedCandidates.forEach { candidate ->
                                    Text(
                                        text = "• ${candidate.label}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MemoryStrongText,
                                    )
                                }
                                attempt.emptyReason?.let { reason ->
                                    Text(
                                        text = "Extraction: $reason",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MemoryMutedText,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                                attempt.rawExtractionOutput?.takeIf(String::isNotBlank)?.let { rawOutput ->
                                    Text(
                                        text = rawOutput,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MemoryMutedText,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                                if (attempt.rejectedCandidates.isNotEmpty() || attempt.rejectionReasons.isNotEmpty()) {
                                    Text(
                                        text = stringResource(Res.string.memory_inspector_rejection_reasons),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MemoryAccent,
                                        modifier = Modifier.padding(top = 6.dp),
                                    )
                                    attempt.rejectedCandidates.forEach { candidate ->
                                        Text(
                                            text = "• ${candidate.label} (${candidate.rejectionReason ?: "rejected"})",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MemoryStrongText,
                                        )
                                    }
                                    attempt.rejectionReasons.forEach { reason ->
                                        Text(
                                            text = "• $reason",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MemoryMutedText,
                                        )
                                    }
                                }
                            }
                            lastInjection?.let { injection ->
                                if (lastWrite != null) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 8.dp),
                                        color = MemoryShellDivider,
                                    )
                                }
                                Text(
                                    text = stringResource(Res.string.memory_inspector_last_injection),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MemoryStrongText,
                                )
                                injection.queryExcerpt?.let { query ->
                                    Text(
                                        text = query,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MemoryStrongText,
                                    )
                                }
                                Text(
                                    text = "${stringResource(Res.string.memory_inspector_injection_count)}: ${injection.selectedRecordCount}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MemoryMutedText,
                                )
                                Text(
                                    text = "${stringResource(Res.string.memory_inspector_estimated_tokens)}: ${injection.estimatedTokens}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MemoryMutedText,
                                )
                                Text(
                                    text = injection.renderedPacket,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MemoryStrongText,
                                )
                            }
                        }
                    }

                    MemoryInspectorCard(title = stringResource(Res.string.memory_inspector_rejected_writes)) {
                        if (state.rejectedWrites.isEmpty()) {
                            MemoryEmptyState(stringResource(Res.string.memory_inspector_no_rejections))
                        } else {
                            state.rejectedWrites.forEach { attempt ->
                                Text(
                                    text = attempt.inputExcerpt.orEmpty(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MemoryStrongText,
                                )
                                attempt.rejectedCandidates.forEach { candidate ->
                                    Text(
                                        text = "• ${candidate.label} (${candidate.rejectionReason ?: "rejected"})",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MemoryMutedText,
                                    )
                                }
                                attempt.rejectionReasons.forEach { reason ->
                                    Text(
                                        text = "• $reason",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MemoryMutedText,
                                    )
                                }
                                if (attempt.rejectedCandidates.isEmpty() && attempt.rejectionReasons.isEmpty()) {
                                    attempt.emptyReason?.let { reason ->
                                        Text(
                                            text = "• extraction=$reason",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MemoryMutedText,
                                        )
                                    }
                                    attempt.rawExtractionOutput?.takeIf(String::isNotBlank)?.let { rawOutput ->
                                        Text(
                                            text = rawOutput,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MemoryMutedText,
                                        )
                                    }
                                }
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    color = MemoryShellDivider,
                                )
                            }
                        }
                    }

                    MemoryInspectorCard(title = stringResource(Res.string.memory_inspector_recent_injections)) {
                        if (state.recentInjections.isEmpty()) {
                            MemoryEmptyState(stringResource(Res.string.memory_inspector_no_injections))
                        } else {
                            state.recentInjections.forEach { injection ->
                                Text(
                                    text = injection.queryExcerpt.orEmpty(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MemoryStrongText,
                                )
                                Text(
                                    text = "${stringResource(Res.string.memory_inspector_injection_count)}: ${injection.selectedRecordCount}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MemoryMutedText,
                                )
                                Text(
                                    text = "${stringResource(Res.string.memory_inspector_estimated_tokens)}: ${injection.estimatedTokens}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MemoryMutedText,
                                )
                                Text(
                                    text = injection.renderedPacket,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MemoryMutedText,
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    color = MemoryShellDivider,
                                )
                            }
                        }
                    }

                    MemoryInspectorCard(title = stringResource(Res.string.memory_inspector_manual_actions)) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = onForgetFact,
                                enabled = state.selectedFactId != null && !state.isWorking,
                                modifier = Modifier.fillMaxWidth().height(40.dp),
                            ) {
                                Text(stringResource(Res.string.memory_inspector_action_forget))
                            }
                            OutlinedButton(
                                onClick = onInvalidateFact,
                                enabled = state.selectedFactId != null && !state.isWorking,
                                modifier = Modifier.fillMaxWidth().height(40.dp),
                                border = BorderStroke(1.dp, MemoryCardBorder),
                            ) {
                                Text(stringResource(Res.string.memory_inspector_action_invalidate))
                            }
                            OutlinedButton(
                                onClick = onRebuildEmbeddings,
                                enabled = !state.isWorking,
                                modifier = Modifier.fillMaxWidth().height(40.dp),
                                border = BorderStroke(1.dp, MemoryCardBorder),
                            ) {
                                Text(stringResource(Res.string.memory_inspector_action_rebuild_embeddings))
                            }
                            if (state.capabilities.consolidationSupported) {
                                OutlinedButton(
                                    onClick = onRunConsolidation,
                                    enabled = !state.isWorking,
                                    modifier = Modifier.fillMaxWidth().height(40.dp),
                                    border = BorderStroke(1.dp, MemoryCardBorder),
                                ) {
                                    Text(stringResource(Res.string.memory_inspector_action_run_consolidation))
                                }
                            }
                            OutlinedButton(
                                onClick = onCopyEvidence,
                                enabled = state.canCopySelectedEvidence && !state.isWorking,
                                modifier = Modifier.fillMaxWidth().height(40.dp),
                                border = BorderStroke(1.dp, MemoryCardBorder),
                            ) {
                                Text(stringResource(Res.string.memory_inspector_action_copy_evidence))
                            }
                            OutlinedButton(
                                onClick = onOpenSourceRef,
                                enabled = state.canOpenSelectedSourceRef && !state.isWorking,
                                modifier = Modifier.fillMaxWidth().height(40.dp),
                                border = BorderStroke(1.dp, MemoryCardBorder),
                            ) {
                                Text(stringResource(Res.string.memory_inspector_action_open_source_ref))
                            }
                            if (state.capabilities.autoWriteSupported) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = stringResource(Res.string.memory_inspector_auto_write),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MemoryStrongText,
                                    )
                                    Switch(
                                        checked = state.capabilities.autoWriteEnabled,
                                        onCheckedChange = onToggleAutoWrite,
                                        enabled = !state.isWorking,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryInspectorCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MemoryCardBackground, RoundedCornerShape(14.dp))
            .border(1.dp, MemoryCardBorderStrong, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
            color = MemoryStrongText,
            fontWeight = FontWeight.SemiBold,
        )
        content()
    }
}

@Composable
private fun MemoryOverviewRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MemoryMutedText)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MemoryStrongText, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MemoryEmptyState(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MemoryMutedText,
    )
}

@Composable
private fun MemoryFactRow(
    fact: MemoryFactRecord,
    isSelected: Boolean,
    objectLabel: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) MemoryCardBackgroundSelected else MemoryCardBackground,
                RoundedCornerShape(10.dp),
            )
            .border(
                1.dp,
                if (isSelected) MemoryCardBorderStrong else MemoryCardBorder,
                RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = fact.predicate.replace('_', ' '),
            style = MaterialTheme.typography.bodyMedium,
            color = MemoryStrongText,
        )
        Text(
            text = objectLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MemoryMutedText,
        )
    }
}

private fun MemoryFactRecord.renderObjectLabel(entitiesById: Map<String?, ru.souz.agent.memory.MemoryEntityRecord>): String =
    objectEntityId?.let { entityId -> entitiesById[entityId]?.displayName }
        ?: objectValueText
        ?: objectValueJson
        ?: objectKind.name.lowercase()
