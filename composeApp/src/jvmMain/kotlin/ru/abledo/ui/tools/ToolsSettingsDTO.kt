package ru.abledo.ui.tools

import ru.abledo.tool.FewShotExample
import ru.abledo.tool.ToolCategory
import ru.abledo.ui.VMEvent
import ru.abledo.ui.VMState
import ru.abledo.ui.VMSideEffect

sealed interface ToolsSettingsEvent : VMEvent {
    data class ToggleCategory(val category: ToolCategory, val enabled: Boolean) : ToolsSettingsEvent
    data class ToggleTool(val category: ToolCategory, val toolName: String, val enabled: Boolean) : ToolsSettingsEvent
    object SaveSettings : ToolsSettingsEvent
}

sealed interface ToolsSettingsEffect : VMSideEffect {
    data class SettingsSaved(val message: String) : ToolsSettingsEffect
}

data class ToolsScreenState(
    val categories: List<ToolsCategoryUi> = emptyList(),
    val isSaving: Boolean = false,
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
