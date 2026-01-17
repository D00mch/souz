@file:OptIn(ExperimentalSharedTransitionApi::class)

package ru.gigadesk.ui.tools

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.* // Используем Material 3
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import org.kodein.di.compose.localDI
import ru.gigadesk.tool.ToolCategory
import ru.gigadesk.ui.AppTheme
import ru.gigadesk.ui.glassColors
import ru.gigadesk.ui.main.RealLiquidGlassCard

private val ToolsWindowSize = DpSize(width = 640.dp, height = 720.dp)

@Composable
fun ToolsScreen(
    onClose: () -> Unit,
    onOpenToolDetails: (ToolCategory, ToolUi) -> Unit = { _, _ -> },
    onResizeRequest: (DpSize) -> Unit = {},
    onShowSnack: (String) -> Unit = {},
    viewModelKey: String = "ToolsScreen",
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
    val di = localDI()
    val viewModel = viewModel(key = viewModelKey) { ToolsSettingsViewModel(di) }
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ToolsSettingsEffect.SettingsSaved -> {
                    onShowSnack(effect.message)
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
        onCategoryExpandedChange = { category, expanded ->
            viewModel.send(ToolsSettingsEvent.UpdateCategoryExpanded(category, expanded))
        },
        onScrollPositionChange = { position ->
            viewModel.send(ToolsSettingsEvent.UpdateScrollPosition(position))
        },
        onToolClick = onOpenToolDetails,
        onSave = { viewModel.send(ToolsSettingsEvent.SaveSettings) },
        onResizeRequest = onResizeRequest,
        onClose = onClose,
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope,
    )
}

@Composable
fun ToolsScreen(
    state: ToolsScreenState,
    onCategoryToggle: (ToolCategory, Boolean) -> Unit,
    onToolToggle: (ToolCategory, String, Boolean) -> Unit,
    onCategoryExpandedChange: (ToolCategory, Boolean) -> Unit,
    onScrollPositionChange: (Int) -> Unit,
    onToolClick: (ToolCategory, ToolUi) -> Unit,
    onSave: () -> Unit,
    onResizeRequest: (DpSize) -> Unit = {},
    onClose: () -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
    LaunchedEffect(Unit) { onResizeRequest(ToolsWindowSize) }

    // Получаем фокус окна
    val windowInfo = LocalWindowInfo.current
    val isFocused = windowInfo.isWindowFocused

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        RealLiquidGlassCard(
            modifier = Modifier.fillMaxSize(),
            isWindowFocused = isFocused // Передаем статус фокуса
        ) {
            val scrollState = rememberScrollState(state.scrollPosition)

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

                        state.categories.forEachIndexed { index, category ->
                            key(category.category) {
                                val expanded = state.expandedByCategory[category.category] ?: false
                                CategorySection(
                                    category = category,
                                    expanded = expanded,
                                    onExpandedChange = { onCategoryExpandedChange(category.category, it) },
                                    onCategoryToggle = onCategoryToggle,
                                    onToolToggle = onToolToggle,
                                    onToolClick = onToolClick,
                                    sharedTransitionScope = sharedTransitionScope,
                                    animatedVisibilityScope = animatedVisibilityScope,
                                )
                            }

                            if (index != state.categories.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 36.dp, top = 8.dp, bottom = 8.dp),
                                    thickness = 1.dp,
                                    color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.10f),
                                )
                            }
                        }
                    }

                    HorizontalDivider(
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
            LaunchedEffect(scrollState) {
                snapshotFlow { scrollState.value }
                    .distinctUntilChanged()
                    .collect { onScrollPositionChange(it) }
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
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
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
            Column(
                modifier = Modifier.padding(start = 16.dp)
            ) {
                category.tools.forEachIndexed { i, tool ->
                    ToolRow(
                        categoryEnabled = category.enabled,
                        category = category.category,
                        tool = tool,
                        onToolToggle = onToolToggle,
                        onToolClick = onToolClick,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                    )


                    if (i != category.tools.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier
                                .fillMaxWidth()
                                .alpha(0.5f)
                                .padding(start = 36.dp),
                            thickness = 1.dp,
                            color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.10f),
                        )
                    }
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
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
) {
    TooltipArea(
        tooltip = {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                Text(
                    text = tool.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface, // Исправил цвет текста тултипа для читаемости на surface
                )
            }
        },
        delayMillis = 900,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val textColor = MaterialTheme.glassColors.textPrimary
            val nameAlpha = if (categoryEnabled) 1f else 0.5f
            val baseColor = textColor.copy(alpha = 0.5f * nameAlpha)
            val fadeWidth = 28.dp

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
            Row(
                modifier = Modifier
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.0001f))
                    .clip(RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .let { base ->
                        if (categoryEnabled) base.clickable { onToolClick(category, tool) } else base
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = tool.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor.copy(alpha = nameAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.sharedToolHeaderElement(
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        key = toolSharedTransitionKey(
                            category = category,
                            toolName = tool.name,
                            suffix = "title",
                        ),
                    ),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = tool.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = baseColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .sharedToolHeaderElement(
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope,
                            key = toolSharedTransitionKey(
                                category = category,
                                toolName = tool.name,
                                suffix = "description",
                            ),
                        )
                        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                        .drawWithCache {
                            val w = fadeWidth.toPx().coerceAtMost(size.width)
                            val mask = Brush.horizontalGradient(
                                colors = listOf(Color.Black, Color.Transparent),
                                startX = size.width - w,
                                endX = size.width
                            )
                            onDrawWithContent {
                                drawContent()
                                // mask only the text layer, not the real background
                                drawRect(brush = mask, blendMode = BlendMode.DstIn)
                            }
                        },
                )
            }
        }
    }
}

private fun toolSharedTransitionKey(
    category: ToolCategory,
    toolName: String,
    suffix: String,
): String = "tool-${category.name}-${toolName}-$suffix"

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun Modifier.sharedToolHeaderElement(
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    key: String,
): Modifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
    with(sharedTransitionScope) {
        this@sharedToolHeaderElement.sharedElement(
            sharedContentState = rememberSharedContentState(key = key),
            animatedVisibilityScope = animatedVisibilityScope,
        )
    }
} else {
    this
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
            onCategoryExpandedChange = { _, _ -> },
            onScrollPositionChange = {},
            sharedTransitionScope = null,
            animatedVisibilityScope = null,
        )
    }
}