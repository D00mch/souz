package ru.souz.ui.tools

import ru.souz.tool.FewShotExample
import ru.souz.tool.ToolCategory
import ru.souz.ui.VMEvent
import ru.souz.ui.VMState
import ru.souz.ui.VMSideEffect

sealed interface ToolsSettingsEvent : VMEvent {
    data class ToggleCategory(val category: ToolCategory, val enabled: Boolean) : ToolsSettingsEvent
    data class ToggleTool(val category: ToolCategory, val toolName: String, val enabled: Boolean) : ToolsSettingsEvent
    data class UpdateCategoryExpanded(val category: ToolCategory, val expanded: Boolean) : ToolsSettingsEvent
    data class UpdateScrollPosition(val position: Int) : ToolsSettingsEvent
    object SaveSettings : ToolsSettingsEvent
}

sealed interface ToolsSettingsEffect : VMSideEffect {
    data class SettingsSaved(val message: String) : ToolsSettingsEffect
}

data class ToolsScreenState(
    val categories: List<ToolsCategoryUi> = emptyList(),
    val isSaving: Boolean = false,
    val expandedByCategory: Map<ToolCategory, Boolean> = emptyMap(),
    val scrollPosition: Int = 0,
) : VMState

data class ToolsCategoryUi(
    val category: ToolCategory,
    val enabled: Boolean,
    val tools: List<ToolUi>,
)

data class ToolUi(
    val name: String,
    val description: String,
    val enabled: Boolean,
    val descriptionOverride: String? = null,
    val examplesOverride: List<FewShotExample>? = null,
)
