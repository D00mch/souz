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
import ru.gigadesk.audio.InMemoryAudioRecorder
import ru.gigadesk.db.DesktopInfoRepository
import ru.gigadesk.db.SettingsProvider
import ru.gigadesk.giga.GigaModel
import ru.gigadesk.ui.BaseViewModel
import ru.gigadesk.ui.main.interactors.ChatInteractor
import ru.gigadesk.ui.main.interactors.MainInteractorOutput
import ru.gigadesk.ui.main.interactors.MainInteractors
import ru.gigadesk.ui.main.interactors.MainInteractorsFactory
import ru.gigadesk.ui.main.interactors.PermissionsInteractor
import ru.gigadesk.ui.main.interactors.SpeechInteractor
import ru.gigadesk.ui.main.interactors.VoiceInputInteractor
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.minutes

class MainViewModel(
    override val di: DI,
) : BaseViewModel<MainState, MainEvent, MainEffect>(), DIAware {

    private val l = LoggerFactory.getLogger(MainViewModel::class.java)

    private val graphAgent by di.instance<GraphBasedAgent>()
    private val desktopInfoRepository: DesktopInfoRepository by di.instance()
    private val settingsProvider: SettingsProvider by di.instance()
    private val mainInteractorsFactory: MainInteractorsFactory by di.instance()

    private val agentRef = AtomicReference<GraphBasedAgent?>(null)

    private val interactors: MainInteractors = mainInteractorsFactory.create(ioDispatchers)
    private val chatInteractor: ChatInteractor = interactors.chat
    private val voiceInputInteractor: VoiceInputInteractor = interactors.voiceInput
    private val speechInteractor: SpeechInteractor = interactors.speech
    private val permissionsInteractor: PermissionsInteractor = interactors.permissions
    private val audioRecorder: InMemoryAudioRecorder = voiceInputInteractor.audioRecorder

    init {
        agentRef.set(graphAgent)
        chatInteractor.bindAgentRef(agentRef)

        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { collectInteractorOutputs() }

        viewModelScope.launch {
            setState {
                copy(
                    selectedModel = settingsProvider.gigaModel.alias,
                    selectedContextSize = settingsProvider.contextSize,
                )
            }
        }

        chatInteractor.start(viewModelScope)
        speechInteractor.start(viewModelScope)
        permissionsInteractor.start(viewModelScope)

        viewModelScope.launch { permissionsInteractor.runOnboardingIfNeeded() }
        ioLaunch {
            voiceInputInteractor.initialize(
                scope = viewModelScope,
                stateProvider = { currentState },
                onRecognizedText = { recognizedText ->
                    withContext(Dispatchers.Main) {
                        chatInteractor.sendChatMessage(
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
            MainEvent.StartListening -> voiceInputInteractor.startRecording(currentState.isListening)
            MainEvent.StopListening -> voiceInputInteractor.stopRecording(currentState.isListening)
            MainEvent.RequestNewConversation -> requestNewConversation()
            MainEvent.ConfirmNewConversation -> confirmNewConversation()
            MainEvent.DismissNewConversationDialog -> dismissNewConversationDialog()
            MainEvent.ClearContext -> clearContext()
            MainEvent.StopSpeech -> chatInteractor.stopSpeechAndSideEffects()
            MainEvent.StopAgentJob -> chatInteractor.cancelActiveJob()
            MainEvent.ShowLastText -> setPreviousText()
            MainEvent.ToggleThinkingPanel -> setState { copy(isThinkingPanelOpen = !isThinkingPanelOpen) }
            is MainEvent.UpdateChatInput -> setState { copy(chatInputText = event.text) }
            is MainEvent.UpdateChatModel -> updateChatModel(event.model)
            is MainEvent.UpdateChatContextSize -> updateChatContextSize(event.size)
            MainEvent.SendChatMessage -> vmLaunch {
                chatInteractor.sendChatMessage(
                    scope = viewModelScope,
                    isVoice = false,
                    chatMessage = currentState.chatInputText.text,
                )
            }

            MainEvent.RefreshSettings -> refreshSettings()
            MainEvent.ApproveToolPermission ->
                permissionsInteractor.resolveToolPermission(currentState.toolPermissionDialog?.requestId, approved = true)

            MainEvent.RejectToolPermission ->
                permissionsInteractor.resolveToolPermission(currentState.toolPermissionDialog?.requestId, approved = false)
        }
    }

    override suspend fun handleSideEffect(effect: MainEffect) {
        when (effect) {
            is MainEffect.ShowError -> l.error(effect.message)
            MainEffect.Hide -> Unit
        }
    }

    private suspend fun collectInteractorOutputs() {
        merge(
            chatInteractor.outputs,
            voiceInputInteractor.outputs,
            speechInteractor.outputs,
            permissionsInteractor.outputs,
        ).collect { output ->
            when (output) {
                is MainInteractorOutput.State -> setState(output.reduce)
                is MainInteractorOutput.Effect -> send(output.effect)
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
        chatInteractor.stopSpeechAndSideEffects()
        chatInteractor.clearContext()

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
        chatInteractor.updateModel(model)
        setState { copy(selectedModel = model.alias) }
    }

    private suspend fun updateChatContextSize(size: Int) {
        if (size <= 0) return
        settingsProvider.contextSize = size
        chatInteractor.updateContextSize(size)
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
            chatInteractor.setContext(ctx)
        }

        val prevText = currentState.lastText ?: return
        setState { copy(displayedText = prevText, lastText = null, userExpectCloseOnX = false) }
    }

    private suspend fun clearContext() {
        val lastKnownAgentContext: AgentContext<String>? = chatInteractor.snapshotContext()
        chatInteractor.clearContext()
        chatInteractor.stopSpeechAndSideEffects()

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
        permissionsInteractor.rejectPendingPermissionRequest(currentState.toolPermissionDialog?.requestId)
        chatInteractor.onCleared()
        permissionsInteractor.onCleared()
    }

    private companion object {
        const val DEFAULT_CLEARED_TEXT = "Контекст очищен"
    }
}
