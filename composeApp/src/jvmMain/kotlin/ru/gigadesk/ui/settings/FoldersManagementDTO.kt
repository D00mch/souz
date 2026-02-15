package ru.gigadesk.ui.settings

import ru.gigadesk.ui.VMEvent
import ru.gigadesk.ui.VMSideEffect
import ru.gigadesk.ui.VMState

data class FoldersManagementState(
    val forbiddenFolders: List<ForbiddenFolderItem> = emptyList(),
): VMState

data class ForbiddenFolderItem(
    val title: String,
    val path: String,
)

sealed interface FoldersManagementEvent : VMEvent {
    data class AddForbiddenFolder(val path: String) : FoldersManagementEvent
    data class RemoveForbiddenFolder(val path: String) : FoldersManagementEvent
    object CloseScreen : FoldersManagementEvent
}

sealed interface FoldersManagementEffect : VMSideEffect {
    object CloseScreen : FoldersManagementEffect
}
