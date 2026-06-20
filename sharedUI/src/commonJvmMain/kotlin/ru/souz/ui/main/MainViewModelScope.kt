package ru.souz.ui.main

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.kodein.di.DI
import org.kodein.di.bindings.Scope
import org.kodein.di.bindings.WeakContextScope
import org.kodein.di.direct
import org.kodein.di.instance
import org.kodein.di.on
import ru.souz.ui.main.usecases.ChatAttachmentsUseCase
import ru.souz.ui.main.usecases.ChatUseCase
import ru.souz.ui.main.usecases.PermissionsUseCase
import ru.souz.ui.main.usecases.SpeechUseCase
import ru.souz.ui.main.usecases.ToolModifyReviewUseCase
import ru.souz.ui.main.usecases.VoiceInputController
import ru.souz.ambient.SemanticBlockBuilder

class MainViewModelScope(
    val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
)

val mainViewModelDiScope: Scope<MainViewModelScope> = WeakContextScope.of()

fun createMainViewModel(
    di: DI,
    scope: MainViewModelScope = MainViewModelScope(),
): MainViewModel {
    val direct = di.direct
    val scopedDirect = direct.on(scope)
    return MainViewModel(
        scopeToken = scope,
        agentFacade = direct.instance(),
        backgroundIndexRefresher = direct.instance(),
        settingsProvider = direct.instance(),
        llmBuildProfile = direct.instance(),
        localModelUiHost = direct.instance(),
        pathOpener = direct.instance(),
        chatCommandInputSource = direct.instance(),
        permissionPromptService = direct.instance(),
        appScope = direct.instance(),
        ambientTranscriptionService = direct.instance(),
        ambientBlockAnalyzer = direct.instance(),
        ambientSemanticBlockBuilder = direct.instance<SemanticBlockBuilder>(),
        chatUseCase = scopedDirect.instance<ChatUseCase>(),
        toolModifyReviewUseCase = scopedDirect.instance<ToolModifyReviewUseCase>(),
        voiceInputUseCase = scopedDirect.instance<VoiceInputController>(),
        speechUseCase = scopedDirect.instance<SpeechUseCase>(),
        permissionsUseCase = scopedDirect.instance<PermissionsUseCase>(),
        attachmentsUseCase = scopedDirect.instance<ChatAttachmentsUseCase>(),
    )
}
