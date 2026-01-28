package ru.gigadesk.ui.settings

import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import org.slf4j.LoggerFactory
import ru.gigadesk.db.SettingsProvider
import ru.gigadesk.ui.BaseViewModel

class FoldersManagementViewModel(
    override val di: DI,
) : BaseViewModel<FoldersManagementState, FoldersManagementEvent, FoldersManagementEffect>(), DIAware {

    private val l = LoggerFactory.getLogger(FoldersManagementViewModel::class.java)
    private val settingsProvider: SettingsProvider by di.instance()

    init {
        val foldersText = settingsProvider.forbiddenFolders.joinToString("\n")
        vmLaunch { setState { copy(forbiddenFoldersInput = foldersText) } }
    }

    override fun initialState(): FoldersManagementState = FoldersManagementState()

    override suspend fun handleEvent(event: FoldersManagementEvent) {
        l.debug("handleEvent: {}", event)
        when (event) {
            is FoldersManagementEvent.InputForbiddenFolders -> {
                val folders = event.folders
                    .lines()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                settingsProvider.forbiddenFolders = folders
                setState { copy(forbiddenFoldersInput = event.folders) }
            }

            FoldersManagementEvent.CloseScreen -> send(FoldersManagementEffect.CloseScreen)
        }
    }

    override suspend fun handleSideEffect(effect: FoldersManagementEffect) = when (effect) {
        FoldersManagementEffect.CloseScreen -> l.debug("ignore effect: {}", effect)
    }
}
