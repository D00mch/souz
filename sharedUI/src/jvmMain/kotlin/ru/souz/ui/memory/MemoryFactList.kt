package ru.souz.ui.memory

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import ru.souz.memory.MemoryFact
import ru.souz.memory.MemoryFactKind
import ru.souz.ui.glassColors
import souz.sharedui.generated.resources.*

@Composable
internal fun MemoryFilters(
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
internal fun MemoryFactsContent(
    state: MemoryUiState,
    onAction: (MemoryAction) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
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
    fact: MemoryFact,
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
                    text = fact.updatedAt.shortMemoryLabel(),
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
            text = "${fact.kind.label()} · ${fact.scope.memoryLabel()} · ${fact.confidence.confidenceLabel()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.58f),
        )

        Text(
            text = fact.body.preview(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.82f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun MemoryInlineError(
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
internal fun MemoryCenteredText(
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
internal fun memoryTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = MaterialTheme.glassColors.textPrimary,
    unfocusedTextColor = MaterialTheme.glassColors.textPrimary,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.35f),
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    unfocusedLabelColor = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.62f),
    cursorColor = MaterialTheme.colorScheme.primary,
)
