@file:OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)

package ru.gigadesk.ui.main

import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import org.slf4j.LoggerFactory
import ru.gigadesk.agent.GraphBasedAgent
import ru.gigadesk.agent.engine.AgentContext
import ru.gigadesk.db.DesktopInfoRepository
import ru.gigadesk.db.SettingsProvider
import ru.gigadesk.giga.GigaModel
import ru.gigadesk.ui.BaseViewModel
import ru.gigadesk.ui.main.usecases.ChatUseCase
import ru.gigadesk.ui.main.usecases.MainUseCaseOutput
import ru.gigadesk.ui.main.usecases.MainUseCases
import ru.gigadesk.ui.main.usecases.MainUseCasesFactory
import ru.gigadesk.ui.main.usecases.PermissionsUseCase
import ru.gigadesk.ui.main.usecases.SpeechUseCase
import ru.gigadesk.ui.main.usecases.VoiceInputUseCase
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.minutes

class MainViewModel(
    override val di: DI,
) : BaseViewModel<MainState, MainEvent, MainEffect>(), DIAware {

    private val l = LoggerFactory.getLogger(MainViewModel::class.java)

    private val graphAgent by di.instance<GraphBasedAgent>()
    private val desktopInfoRepository: DesktopInfoRepository by di.instance()
    private val settingsProvider: SettingsProvider by di.instance()
    private val mainUseCasesFactory: MainUseCasesFactory by di.instance()

    private val agentRef = AtomicReference<GraphBasedAgent?>(null)

    private val useCases: MainUseCases = mainUseCasesFactory.create(ioDispatchers)
    private val chatUseCase: ChatUseCase = useCases.chat
    private val voiceInputUseCase: VoiceInputUseCase = useCases.voiceInput
    private val speechUseCase: SpeechUseCase = useCases.speech
    private val permissionsUseCase: PermissionsUseCase = useCases.permissions

    init {
        agentRef.set(graphAgent)
        chatUseCase.bindAgentRef(agentRef)

        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { collectUseCaseOutputs() }

        viewModelScope.launch {
            setState {
                copy(
                    selectedModel = settingsProvider.gigaModel.alias,
                    selectedContextSize = settingsProvider.contextSize,
                )
            }
        }

        chatUseCase.start(viewModelScope)
        speechUseCase.start(viewModelScope)
        permissionsUseCase.start(viewModelScope)

        viewModelScope.launch { permissionsUseCase.runOnboardingIfNeeded() }
        ioLaunch {
            voiceInputUseCase.initialize(
                scope = viewModelScope,
                stateProvider = { currentState },
                onRecognizedText = { recognizedText ->
                    withContext(Dispatchers.Main) {
                        chatUseCase.sendChatMessage(
                            scope = viewModelScope,
                            isVoice = true,
                            chatMessage = recognizedText,
                        )
                    }
                },
            )
        }
        viewModelScope.launchDbSetup(desktopInfoRepository)
    }

    override fun initialState(): MainState = MainState()

    override suspend fun handleEvent(event: MainEvent) {
        when (event) {
            MainEvent.StartListening -> voiceInputUseCase.startRecording(currentState.isListening)
            MainEvent.StopListening -> voiceInputUseCase.stopRecording(currentState.isListening)
            MainEvent.RequestNewConversation -> requestNewConversation()
            MainEvent.ConfirmNewConversation -> confirmNewConversation()
            MainEvent.DismissNewConversationDialog -> dismissNewConversationDialog()
            MainEvent.ClearContext -> clearContext()
            MainEvent.StopSpeech -> chatUseCase.stopSpeechAndSideEffects()
            MainEvent.StopAgentJob -> chatUseCase.cancelActiveJob()
            MainEvent.ShowLastText -> setPreviousText()
            MainEvent.ToggleThinkingPanel -> setState { copy(isThinkingPanelOpen = !isThinkingPanelOpen) }
            is MainEvent.UpdateChatInput -> setState { copy(chatInputText = event.text) }
            is MainEvent.UpdateChatModel -> updateChatModel(event.model)
            is MainEvent.UpdateChatContextSize -> updateChatContextSize(event.size)
            MainEvent.SendChatMessage -> vmLaunch {
                chatUseCase.sendChatMessage(
                    scope = viewModelScope,
                    isVoice = false,
                    chatMessage = currentState.chatInputText.text,
                )
            }

            MainEvent.RefreshSettings -> refreshSettings()
            MainEvent.ApproveToolPermission ->
                permissionsUseCase.resolveToolPermission(currentState.toolPermissionDialog?.requestId, approved = true)

            MainEvent.RejectToolPermission ->
                permissionsUseCase.resolveToolPermission(currentState.toolPermissionDialog?.requestId, approved = false)
        }
    }

    override suspend fun handleSideEffect(effect: MainEffect) {
        when (effect) {
            is MainEffect.ShowError -> l.error(effect.message)
            MainEffect.Hide -> Unit
        }
    }

    private suspend fun collectUseCaseOutputs() {
        merge(
            chatUseCase.outputs,
            voiceInputUseCase.outputs,
            speechUseCase.outputs,
            permissionsUseCase.outputs,
        ).collect { output ->
            when (output) {
                is MainUseCaseOutput.State -> setState(output.reduce)
                is MainUseCaseOutput.Effect -> send(output.effect)
            }
        }
    }

    private fun CoroutineScope.launchDbSetup(repo: DesktopInfoRepository): Job = launch(ioDispatchers) {
        while (isActive) {
            repo.storeDesktopDataDaily()
            delay(5.minutes)
        }
    }

    private suspend fun requestNewConversation() {
        if (currentState.chatMessages.isEmpty()) {
            startNewConversation()
            return
        }

        setState { copy(showNewChatDialog = true) }
    }

    private suspend fun confirmNewConversation() {
        setState { copy(showNewChatDialog = false) }
        startNewConversation()
    }

    private suspend fun dismissNewConversationDialog() {
        setState { copy(showNewChatDialog = false) }
    }

    private suspend fun startNewConversation() {
        chatUseCase.stopSpeechAndSideEffects()
        chatUseCase.clearContext()

        setState {
            copy(
                displayedText = MainState.randomStatusTip(),
                statusMessage = "",
                lastText = null,
                lastKnownAgentContext = null,
                userExpectCloseOnX = false,
                isProcessing = false,
                chatMessages = emptyList(),
                chatStartTip = MainState.randomStatusTip(),
                chatInputText = TextFieldValue(""),
                showNewChatDialog = false,
            )
        }
    }

    private suspend fun updateChatModel(modelAlias: String) {
        val model = GigaModel.entries.firstOrNull { it.alias == modelAlias } ?: return
        settingsProvider.gigaModel = model
        chatUseCase.updateModel(model)
        setState { copy(selectedModel = model.alias) }
    }

    private suspend fun updateChatContextSize(size: Int) {
        if (size <= 0) return
        settingsProvider.contextSize = size
        chatUseCase.updateContextSize(size)
        setState { copy(selectedContextSize = size) }
    }

    private suspend fun refreshSettings() {
        setState {
            copy(
                selectedModel = settingsProvider.gigaModel.alias,
                selectedContextSize = settingsProvider.contextSize,
            )
        }
    }

    private suspend fun setPreviousText() {
        currentState.lastKnownAgentContext?.let { ctx ->
            chatUseCase.setContext(ctx)
        }

        val prevText = currentState.lastText ?: return
        setState { copy(displayedText = prevText, lastText = null, userExpectCloseOnX = false) }
    }

    private suspend fun clearContext() {
        val lastKnownAgentContext: AgentContext<String>? = chatUseCase.snapshotContext()
        chatUseCase.clearContext()
        chatUseCase.stopSpeechAndSideEffects()

        when (currentState.userExpectCloseOnX) {
            false -> {
                val currentText = currentState.displayedText
                val clearedText = "$DEFAULT_CLEARED_TEXT. Нажмите еще раз, чтобы скрыть."
                val lastText = if (currentText == DEFAULT_CLEARED_TEXT || MainState.START_TIPS.contains(currentText)) {
                    null
                } else {
                    currentText
                }
                setState {
                    copy(
                        displayedText = clearedText,
                        lastText = lastText,
                        lastKnownAgentContext = lastKnownAgentContext ?: currentState.lastKnownAgentContext,
                        userExpectCloseOnX = true,
                        chatMessages = emptyList(),
                        chatStartTip = MainState.randomStatusTip(),
                        showNewChatDialog = false,
                    )
                }
            }

            true -> {
                setState {
                    copy(
                        displayedText = DEFAULT_CLEARED_TEXT,
                        userExpectCloseOnX = false,
                        chatMessages = emptyList(),
                        chatStartTip = MainState.randomStatusTip(),
                        showNewChatDialog = false,
                    )
                }
                send(MainEffect.Hide)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        permissionsUseCase.rejectPendingPermissionRequest(currentState.toolPermissionDialog?.requestId)
        chatUseCase.onCleared()
        permissionsUseCase.onCleared()
    }

    private companion object {
        const val DEFAULT_CLEARED_TEXT = "Контекст очищен"
    }
}
