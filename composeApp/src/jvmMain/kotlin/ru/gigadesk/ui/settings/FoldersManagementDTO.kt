package ru.gigadesk.ui.settings

import ru.gigadesk.ui.VMEvent
import ru.gigadesk.ui.VMSideEffect
import ru.gigadesk.ui.VMState

data class FoldersManagementState(
    val forbiddenFoldersInput: String = "",
): VMState

sealed interface FoldersManagementEvent : VMEvent {
    data class InputForbiddenFolders(val folders: String) : FoldersManagementEvent
    object CloseScreen : FoldersManagementEvent
}

sealed interface FoldersManagementEffect : VMSideEffect {
    object CloseScreen : FoldersManagementEffect
}
