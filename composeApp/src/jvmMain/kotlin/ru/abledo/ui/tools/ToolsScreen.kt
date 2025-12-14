package ru.abledo.ui.tools

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.Divider
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.kodein.di.compose.localDI
import ru.abledo.tool.ToolCategory
import ru.abledo.ui.AppTheme
import ru.abledo.ui.glassColors
import ru.abledo.ui.main.GlassCard

private val ToolsWindowSize = DpSize(width = 640.dp, height = 720.dp)

@Composable
fun ToolsScreen(
    onClose: () -> Unit,
    onOpenToolDetails: (ToolCategory, ToolUi) -> Unit = { _, _ -> },
    onResizeRequest: (DpSize) -> Unit = {},
    onShowSnackbar: (String) -> Unit = {},
    viewModelKey: String = "ToolsScreen",
) {
    val di = localDI()
    val viewModel = viewModel(key = viewModelKey) { ToolsSettingsViewModel(di) }
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ToolsSettingsEffect.SettingsSaved -> {
                    onShowSnackbar(effect.message)
                }
            }
        }
    }

    ToolsScreen(
        state = state,
        onCategoryToggle = { category, enabled ->
            viewModel.send(ToolsSettingsEvent.ToggleCategory(category, enabled))
        },
        onToolToggle = { category, toolName, enabled ->
            viewModel.send(ToolsSettingsEvent.ToggleTool(category, toolName, enabled))
        },
        onToolClick = onOpenToolDetails,
        onSave = { viewModel.send(ToolsSettingsEvent.SaveSettings) },
        onResizeRequest = onResizeRequest,
        onClose = onClose,
    )
}

@Composable
fun ToolsScreen(
    state: ToolsScreenState,
    onCategoryToggle: (ToolCategory, Boolean) -> Unit,
    onToolToggle: (ToolCategory, String, Boolean) -> Unit,
    onToolClick: (ToolCategory, ToolUi) -> Unit,
    onSave: () -> Unit,
    onResizeRequest: (DpSize) -> Unit = {},
    onClose: () -> Unit,
) {
    LaunchedEffect(Unit) { onResizeRequest(ToolsWindowSize) }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        GlassCard(modifier = Modifier.fillMaxSize()) {
            val scrollState = rememberScrollState()
            val expandedByCategory = remember { mutableStateMapOf<ToolCategory, Boolean>() }

            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(scrollState)
                            .padding(horizontal = 24.dp, vertical = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "Tools",
                                    style = MaterialTheme.typography.headlineLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.glassColors.textPrimary
                                )
                                Text(
                                    text = "Выберите доступные категории и функции",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.7f)
                                )
                            }
                            IconButton(onClick = onClose) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Закрыть",
                                    tint = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.8f)
                                )
                            }
                        }

                        state.categories.forEach { category ->
                            key(category.category) {
                                val expanded = expandedByCategory[category.category] ?: false
                                CategorySection(
                                    category = category,
                                    expanded = expanded,
                                    onExpandedChange = { expandedByCategory[category.category] = it },
                                    onCategoryToggle = onCategoryToggle,
                                    onToolToggle = onToolToggle,
                                    onToolClick = onToolClick,
                                )
                            }
                        }
                    }

                    Divider(
                        modifier = Modifier.padding(horizontal = 24.dp),
                        color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.2f)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Button(
                            onClick = onSave,
                            enabled = !state.isSaving,
                        ) {
                            Text(
                                text = if (state.isSaving) "Сохранение..." else "Save",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.glassColors.textPrimary,
                            )
                        }
                    }
                }

                VerticalScrollbar(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(vertical = 24.dp),
                    adapter = rememberScrollbarAdapter(scrollState)
                )
            }
        }

    }
}

@Composable
private fun CategorySection(
    category: ToolsCategoryUi,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onCategoryToggle: (ToolCategory, Boolean) -> Unit,
    onToolToggle: (ToolCategory, String, Boolean) -> Unit,
    onToolClick: (ToolCategory, ToolUi) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Checkbox(
                checked = category.enabled,
                onCheckedChange = { onCategoryToggle(category.category, it) },
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary,
                    uncheckedColor = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.6f),
                    checkmarkColor = MaterialTheme.colorScheme.onPrimary
                ),
            )
            Text(
                text = category.category.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.glassColors.textPrimary,
                modifier = Modifier.clickable {
                    onExpandedChange(!expanded)
                }
            )

            Spacer(modifier = Modifier.weight(1f))

            IconButton(onClick = { onExpandedChange(!expanded) }) {
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Свернуть" else "Развернуть",
                    tint = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.8f),
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 12.dp)) {
                category.tools.forEach { tool ->
                    ToolRow(
                        categoryEnabled = category.enabled,
                        category = category.category,
                        tool = tool,
                        onToolToggle = onToolToggle,
                        onToolClick = onToolClick,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolRow(
    categoryEnabled: Boolean,
    category: ToolCategory,
    tool: ToolUi,
    onToolToggle: (ToolCategory, String, Boolean) -> Unit,
    onToolClick: (ToolCategory, ToolUi) -> Unit,
) {
    TooltipArea(
        tooltip = {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(8.dp)
            ) {
                Text(
                    text = tool.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.glassColors.textPrimary,
                )
            }
        },
        delayMillis = 200,
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(8.dp, 8.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.0001f)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Checkbox(
                checked = categoryEnabled && tool.enabled,
                onCheckedChange = { onToolToggle(category, tool.name, it) },
                enabled = categoryEnabled,
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary,
                    uncheckedColor = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.6f),
                    checkmarkColor = MaterialTheme.colorScheme.onPrimary
                ),
            )
            Text(
                text = tool.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.glassColors.textPrimary.copy(alpha = if (categoryEnabled) 1f else 0.5f),
                modifier = Modifier
                    .padding(vertical = 6.dp)
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.0001f))
                    .clip(RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .let { base ->
                        if (categoryEnabled) base.clickable { onToolClick(category, tool) } else base
                    },
            )
        }
    }
}

@Preview
@Composable
private fun ToolsScreenPreview() {
    AppTheme {
        ToolsScreen(
            state = ToolsScreenState(
                categories = listOf(
                    ToolsCategoryUi(
                        category = ToolCategory.FILES,
                        enabled = true,
                        tools = listOf(
                            ToolUi("ReadFile", "Read a file", true),
                            ToolUi("ListFiles", "List project files", false),
                        )
                    ),
                    ToolsCategoryUi(
                        category = ToolCategory.DATAANALYTICS,
                        enabled = false,
                        tools = listOf(
                            ToolUi("CreatePlotFromCsv", "Plot data from CSV", true),
                        )
                    )
                )
            ),
            onCategoryToggle = { _, _ -> },
            onToolToggle = { _, _, _ -> },
            onToolClick = { _, _ -> },
            onSave = {},
            onResizeRequest = {},
            onClose = {},
        )
    }
}
