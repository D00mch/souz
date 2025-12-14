package ru.abledo.ui.tools

import ru.abledo.tool.FewShotExample
import ru.abledo.tool.ToolCategory
import ru.abledo.ui.VMEvent
import ru.abledo.ui.VMState
import ru.abledo.ui.VMSideEffect

sealed interface ToolDetailsEvent : VMEvent {
    data class UpdateDescription(val value: String) : ToolDetailsEvent
    data class ToggleEnabled(val enabled: Boolean) : ToolDetailsEvent
    object AddExample : ToolDetailsEvent
    data class RemoveExample(val id: String) : ToolDetailsEvent
    data class UpdateExampleRequest(val id: String, val value: String) : ToolDetailsEvent
    data class UpdateExampleParams(val id: String, val value: String) : ToolDetailsEvent
    object Save : ToolDetailsEvent
    object ResetToDefault : ToolDetailsEvent
}

sealed interface ToolDetailsEffect : VMSideEffect {
    data class Saved(val message: String) : ToolDetailsEffect
}

data class ToolExampleUi(
    val id: String,
    val request: String,
    val paramsJson: String,
    val paramsError: String? = null,
)

data class ToolDetailsState(
    val category: ToolCategory? = null,
    val toolName: String? = null,
    val description: String = "",
    val enabled: Boolean = true,
    val examples: List<ToolExampleUi> = emptyList(),
    val defaultDescription: String = "",
    val defaultExamples: List<FewShotExample> = emptyList(),
    val defaultEnabled: Boolean = true,
    val isSaving: Boolean = false,
    val error: String? = null,
) : VMState
