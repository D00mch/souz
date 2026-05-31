package ru.souz.ui.settings

import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import org.slf4j.LoggerFactory
import ru.souz.db.SettingsProvider
import ru.souz.ui.BaseViewModel
import ru.souz.ui.common.PathMetadataProvider
import ru.souz.ui.main.usecases.PathPicker
import java.util.Locale

class FoldersManagementViewModel(
    override val di: DI,
) : BaseViewModel<FoldersManagementState, FoldersManagementEvent, FoldersManagementEffect>(), DIAware {

    private val l = LoggerFactory.getLogger(FoldersManagementViewModel::class.java)
    private val settingsProvider: SettingsProvider by di.instance()
    private val pathMetadataProvider: PathMetadataProvider by di.instance()
    private val pathPicker: PathPicker by di.instance()

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
                val target = pathMetadataProvider.normalizePath(event.path) ?: return
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
        val selectedPath = pathPicker.pickDirectory()
            .getOrElse { error ->
                l.warn("Failed to pick folder", error)
                return
            }
            ?: return
        addForbiddenFolder(selectedPath)
    }

    private suspend fun addForbiddenFolder(path: String) {
        val newFolder = pathMetadataProvider.normalizePath(path) ?: return
        val updated = (currentState.forbiddenFolders.map { it.path } + newFolder)
            .distinctBy { it.lowercase(Locale.ROOT) }
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
            .mapNotNull(pathMetadataProvider::normalizePath)
            .distinctBy { it.lowercase(Locale.ROOT) }
            .map { path ->
                ForbiddenFolderItem(
                    title = pathMetadataProvider.displayName(path),
                    path = path
                )
            }
}
