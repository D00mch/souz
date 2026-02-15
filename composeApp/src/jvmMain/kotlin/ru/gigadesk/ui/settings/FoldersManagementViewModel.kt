package ru.gigadesk.ui.settings

import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import org.slf4j.LoggerFactory
import ru.gigadesk.db.SettingsProvider
import ru.gigadesk.ui.BaseViewModel
import ru.gigadesk.ui.common.FinderService

class FoldersManagementViewModel(
    override val di: DI,
) : BaseViewModel<FoldersManagementState, FoldersManagementEvent, FoldersManagementEffect>(), DIAware {

    private val l = LoggerFactory.getLogger(FoldersManagementViewModel::class.java)
    private val settingsProvider: SettingsProvider by di.instance()

    init {
        vmLaunch { refreshFoldersState(settingsProvider.forbiddenFolders) }
    }

    override fun initialState(): FoldersManagementState = FoldersManagementState()

    override suspend fun handleEvent(event: FoldersManagementEvent) {
        l.debug("handleEvent: {}", event)
        when (event) {
            is FoldersManagementEvent.AddForbiddenFolder -> {
                val newFolder = FinderService.normalizePath(event.path) ?: return
                val updated = (currentState.forbiddenFolders.map { it.path } + newFolder)
                    .distinctBy { it.lowercase() }
                refreshFoldersState(updated)
            }

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

    private suspend fun refreshFoldersState(rawFolders: List<String>) {
        val normalizedFolders = rawFolders
            .mapNotNull(FinderService::normalizePath)
            .distinctBy { it.lowercase() }

        settingsProvider.forbiddenFolders = normalizedFolders
        setState {
            copy(
                forbiddenFolders = normalizedFolders.map { path ->
                    ForbiddenFolderItem(
                        title = FinderService.displayName(path),
                        path = path
                    )
                }
            )
        }
    }
}
