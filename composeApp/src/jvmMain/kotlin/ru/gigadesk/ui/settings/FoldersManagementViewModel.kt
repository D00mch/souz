package ru.gigadesk.ui.settings

import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import org.slf4j.LoggerFactory
import ru.gigadesk.db.SettingsProvider
import ru.gigadesk.ui.BaseViewModel
import ru.gigadesk.ui.common.FinderService
import javax.swing.JFileChooser
import gigadesk.composeapp.generated.resources.Res
import gigadesk.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

class FoldersManagementViewModel(
    override val di: DI,
) : BaseViewModel<FoldersManagementState, FoldersManagementEvent, FoldersManagementEffect>(), DIAware {

    private val l = LoggerFactory.getLogger(FoldersManagementViewModel::class.java)
    private val settingsProvider: SettingsProvider by di.instance()

    init {
        vmLaunch {
            setState {
                copy(forbiddenFolders = mapFoldersToItems(settingsProvider.forbiddenFolders))
            }
        }
    }

    override fun initialState(): FoldersManagementState = FoldersManagementState()

    override suspend fun handleEvent(event: FoldersManagementEvent) {
        l.debug("handleEvent: {}", event)
        when (event) {
            FoldersManagementEvent.BrowseFolder -> browseFolder()

            is FoldersManagementEvent.AddForbiddenFolder -> addForbiddenFolder(event.path)

            is FoldersManagementEvent.RemoveForbiddenFolder -> {
                val target = FinderService.normalizePath(event.path) ?: return
                val updated = currentState.forbiddenFolders
                    .map { it.path }
                    .filterNot { it.equals(target, ignoreCase = true) }
                refreshFoldersState(updated)
            }

            FoldersManagementEvent.CloseScreen -> send(FoldersManagementEffect.CloseScreen)
        }
    }

    override suspend fun handleSideEffect(effect: FoldersManagementEffect) = when (effect) {
        FoldersManagementEffect.CloseScreen -> l.debug("ignore effect: {}", effect)
    }

    private suspend fun browseFolder() {
        val selectedPath = chooseFolderFromFinder() ?: return
        addForbiddenFolder(selectedPath)
    }

    private suspend fun addForbiddenFolder(path: String) {
        val newFolder = FinderService.normalizePath(path) ?: return
        val updated = (currentState.forbiddenFolders.map { it.path } + newFolder)
            .distinctBy { it.lowercase() }
        refreshFoldersState(updated)
    }

    private suspend fun refreshFoldersState(rawFolders: List<String>) {
        val folders = mapFoldersToItems(rawFolders)
        settingsProvider.forbiddenFolders = folders.map { it.path }
        setState {
            copy(
                forbiddenFolders = folders
            )
        }
    }

    private fun mapFoldersToItems(rawFolders: List<String>): List<ForbiddenFolderItem> =
        rawFolders
            .mapNotNull(FinderService::normalizePath)
            .distinctBy { it.lowercase() }
            .map { path ->
                ForbiddenFolderItem(
                    title = FinderService.displayName(path),
                    path = path
                )
            }

    private suspend fun chooseFolderFromFinder(): String? {
        val title = getString(Res.string.title_select_folder)
        val chooser = JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isMultiSelectionEnabled = false
            isAcceptAllFileFilterUsed = false
        }
        val result = chooser.showOpenDialog(null)
        if (result != JFileChooser.APPROVE_OPTION) return null

        val selected = chooser.selectedFile ?: return null
        return runCatching { selected.canonicalPath }.getOrElse { selected.absolutePath }
    }
}
