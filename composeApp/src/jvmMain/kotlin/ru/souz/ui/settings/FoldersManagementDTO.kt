package ru.souz.ui.settings

import ru.souz.ui.VMEvent
import ru.souz.ui.VMSideEffect
import ru.souz.ui.VMState

data class FoldersManagementState(
    val forbiddenFolders: List<ForbiddenFolderItem> = emptyList(),
): VMState

data class ForbiddenFolderItem(
    val title: String,
    val path: String,
)

sealed interface FoldersManagementEvent : VMEvent {
    object BrowseFolder : FoldersManagementEvent
    data class AddForbiddenFolder(val path: String) : FoldersManagementEvent
    data class RemoveForbiddenFolder(val path: String) : FoldersManagementEvent
    object CloseScreen : FoldersManagementEvent
}

sealed interface FoldersManagementEffect : VMSideEffect {
    object CloseScreen : FoldersManagementEffect
}
