@file:OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)

package ru.souz.ui.main

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
import ru.souz.agent.AgentFacade
import ru.souz.agent.engine.AgentContext
import ru.souz.db.DesktopInfoRepository
import ru.souz.db.SettingsProvider
import ru.souz.giga.GigaModel
import ru.souz.giga.LlmBuildProfile
import ru.souz.ui.BaseViewModel
import ru.souz.ui.main.usecases.ChatUseCase
import ru.souz.ui.main.usecases.MainUseCaseOutput
import ru.souz.ui.main.usecases.MainUseCases
import ru.souz.ui.main.usecases.MainUseCasesFactory
import ru.souz.ui.main.usecases.PermissionsUseCase
import ru.souz.ui.main.usecases.SpeechUseCase
import ru.souz.ui.main.usecases.VoiceInputUseCase
import ru.souz.telemetry.TelemetryConversationEndReason
import ru.souz.telemetry.TelemetryRequestSource
import ru.souz.ui.settings.availableLlmModels
import ru.souz.ui.settings.defaultLlmModel
import kotlin.time.Duration.Companion.minutes
import souz.composeapp.generated.resources.Res
import souz.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.getStringArray
import java.awt.datatransfer.Transferable

class MainViewModel(
    override val di: DI,
) : BaseViewModel<MainState, MainEvent, MainEffect>(), DIAware {

    private val l = LoggerFactory.getLogger(MainViewModel::class.java)

    private val agentFacade: AgentFacade by di.instance()
    private val desktopInfoRepository: DesktopInfoRepository by di.instance()
    private val settingsProvider: SettingsProvider by di.instance()
    private val llmBuildProfile: LlmBuildProfile by di.instance()
    private val mainUseCasesFactory: MainUseCasesFactory by di.instance()

    private val telegramBotController: ru.souz.service.telegram.TelegramBotController by di.instance()
    private var lastAppliedAgentId = agentFacade.activeAgentId.value

    private val useCases: MainUseCases = mainUseCasesFactory.create(ioDispatchers)
    private val chatUseCase: ChatUseCase = useCases.chat
    private val voiceInputUseCase: VoiceInputUseCase = useCases.voiceInput
    private val speechUseCase: SpeechUseCase = useCases.speech
    private val permissionsUseCase: PermissionsUseCase = useCases.permissions
    private val attachmentsUseCase = useCases.attachments
    private var startTips: List<String> = emptyList()

    init {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { collectUseCaseOutputs() }

        viewModelScope.launch {
            startTips = getStringArray(Res.array.start_tips)
            val randomTip = startTips.randomOrNull() ?: ""
            val availableModels = settingsProvider.availableLlmModels(llmBuildProfile)
            val selectedModel = pickConfiguredOrDefaultModel(availableModels)
            applySelectedModel(selectedModel)

            setState {
                copy(
                    selectedModel = selectedModel.alias,
                    availableModelAliases = availableModels.map { it.alias },
                    selectedContextSize = settingsProvider.contextSize,
                    displayedText = randomTip,
                    chatStartTip = randomTip
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
                        if (settingsProvider.voiceInputReviewEnabled) {
                            setState {
                                copy(
                                    pendingVoiceInputDraft = recognizedText.trim(),
                                    pendingVoiceInputDraftToken = pendingVoiceInputDraftToken + 1,
                                )
                            }
                        } else {
                            chatUseCase.sendChatMessage(
                                scope = viewModelScope,
                                isVoice = true,
                                chatMessage = recognizedText,
                                requestSource = TelemetryRequestSource.VOICE_INPUT,
                            )
                        }
                    }
                },
            )
        }
        viewModelScope.launchDbSetup(desktopInfoRepository)

        viewModelScope.launch {
            telegramBotController.incomingMessages.collect { msg ->
                chatUseCase.sendChatMessage(
                    scope = this,
                    isVoice = msg.isVoice,
                    chatMessage = msg.text,
                    requestSource = TelemetryRequestSource.TELEGRAM_BOT,
                    onResult = { result ->
                        result.onSuccess { msg.responseDeferred.complete(it) }
                        result.onFailure { msg.responseDeferred.complete("Error: ${it.message}") }
                    }
                )
            }
        }

        viewModelScope.launch {
            telegramBotController.cleanCommands.collect {
                startNewConversation()
            }
        }

        viewModelScope.launch {
            var firstEmission = true
            agentFacade.activeAgentId.collect { agentId ->
                if (firstEmission) {
                    firstEmission = false
                    lastAppliedAgentId = agentId
                    return@collect
                }
                if (agentId == lastAppliedAgentId) return@collect
                lastAppliedAgentId = agentId
                startNewConversation()
            }
        }
    }

    override fun initialState(): MainState = MainState()

    override suspend fun handleEvent(event: MainEvent) {
        when (event) {
            MainEvent.StartListening -> voiceInputUseCase.startRecording(viewModelScope, currentState.isListening)
            MainEvent.StopListening -> voiceInputUseCase.stopRecording(currentState.isListening)
            is MainEvent.ConsumePendingVoiceInputDraft -> {
                if (event.token == currentState.pendingVoiceInputDraftToken) {
                    setState { copy(pendingVoiceInputDraft = null) }
                }
            }
            MainEvent.RequestNewConversation -> requestNewConversation()
            MainEvent.ConfirmNewConversation -> confirmNewConversation()
            MainEvent.DismissNewConversationDialog -> dismissNewConversationDialog()
            MainEvent.ClearContext -> clearContext()
            MainEvent.StopSpeech -> chatUseCase.stopSpeechAndSideEffects()
            MainEvent.UserPressStop -> chatUseCase.stopCurrentExecution()
            MainEvent.ShowLastText -> setPreviousText()
            MainEvent.ToggleThinkingPanel -> setState { copy(isThinkingPanelOpen = !isThinkingPanelOpen) }
            is MainEvent.UpdateChatModel -> updateChatModel(event.model)
            is MainEvent.UpdateChatContextSize -> updateChatContextSize(event.size)
            MainEvent.PickChatAttachments -> pickChatAttachments()
            is MainEvent.AttachDroppedFiles -> addAttachedFiles(event.paths)
            is MainEvent.RemoveChatAttachment -> removeAttachedFile(event.path)
            is MainEvent.OpenPath -> {
                ru.souz.ui.common.FinderService.openInFinder(event.path)
                    .onFailure { error ->
                        send(MainEffect.ShowError(error.message ?: getString(Res.string.error_failed_to_open_path)))
                    }
            }
            is MainEvent.SendChatMessage -> handleSendChatMessage(event)

            MainEvent.RefreshSettings -> refreshSettings()
            MainEvent.ApproveToolPermission ->
                permissionsUseCase.resolveToolPermission(currentState.toolPermissionDialog?.requestId, approved = true)

            MainEvent.RejectToolPermission ->
                permissionsUseCase.resolveToolPermission(currentState.toolPermissionDialog?.requestId, approved = false)

            is MainEvent.SelectApprovalCandidate ->
                permissionsUseCase.resolveSelectionDialog(
                    sourceId = currentState.selectionDialog?.sourceId,
                    requestId = currentState.selectionDialog?.requestId,
                    selectedCandidateId = event.candidateId,
                )

            MainEvent.CancelSelectionDialog ->
                permissionsUseCase.resolveSelectionDialog(
                    sourceId = currentState.selectionDialog?.sourceId,
                    requestId = currentState.selectionDialog?.requestId,
                    selectedCandidateId = null,
                )
        }
    }

    fun onAttachDroppedTransferable(transferable: Transferable) {
        val droppedPaths = attachmentsUseCase.extractDroppedFilePathsNow(transferable)
        if (droppedPaths.isEmpty()) return
        send(MainEvent.AttachDroppedFiles(droppedPaths))
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
        chatUseCase.finishCurrentConversation(TelemetryConversationEndReason.NEW_CONVERSATION)
        chatUseCase.clearContext()

        setState {
            copy(
                displayedText = startTips.randomOrNull() ?: "",
                statusMessage = "",
                lastText = null,
                lastKnownAgentContext = null,
                userExpectCloseOnX = false,
                isProcessing = false,
                chatMessages = emptyList(),
                chatStartTip = startTips.randomOrNull() ?: "",
                chatSessionId = chatSessionId + 1,
                attachedFiles = emptyList(),
                pendingVoiceInputDraft = null,
                showNewChatDialog = false,
            )
        }
    }

    private fun handleSendChatMessage(event: MainEvent.SendChatMessage) {
        vmLaunch {
            val inputText = event.text
            val attachments = currentState.attachedFiles
            val composedMessage = attachmentsUseCase.buildChatMessageWithAttachedPaths(
                input = inputText,
                attachedFiles = attachments,
            )
            if (composedMessage.isBlank()) return@vmLaunch

            setState { copy(attachedFiles = emptyList()) }
            chatUseCase.sendChatMessage(
                scope = viewModelScope,
                isVoice = false,
                chatMessage = composedMessage,
                displayMessage = inputText,
                attachedFiles = attachments,
                requestSource = TelemetryRequestSource.CHAT_UI,
            )
        }
    }

    private suspend fun pickChatAttachments() {
        val selectedPaths = attachmentsUseCase.pickFilesFromFinder()
            .getOrElse { error ->
                send(MainEffect.ShowError(error.message ?: getString(Res.string.error_failed_to_pick_files)))
                return
            }
        addAttachedFiles(selectedPaths)
    }

    private suspend fun addAttachedFiles(paths: List<String>) {
        if (paths.isEmpty()) return
        val updated = attachmentsUseCase.addFiles(
            existing = currentState.attachedFiles,
            rawPaths = paths,
        )
        setState { copy(attachedFiles = updated) }
    }

    private suspend fun removeAttachedFile(path: String) {
        val updated = attachmentsUseCase.removeFile(
            existing = currentState.attachedFiles,
            rawPath = path,
        )
        setState { copy(attachedFiles = updated) }
    }

    private suspend fun updateChatModel(modelAlias: String) {
        val availableModels = settingsProvider.availableLlmModels(llmBuildProfile)
        val model = availableModels.firstOrNull { it.alias == modelAlias } ?: return
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
        val availableModels = settingsProvider.availableLlmModels(llmBuildProfile)
        val selectedModel = pickConfiguredOrDefaultModel(availableModels)
        applySelectedModel(selectedModel)

        setState {
            copy(
                selectedModel = selectedModel.alias,
                availableModelAliases = availableModels.map { it.alias },
                selectedContextSize = settingsProvider.contextSize,
            )
        }
    }

    private fun pickConfiguredOrDefaultModel(availableModels: List<GigaModel>) = when {
        availableModels.isEmpty() -> settingsProvider.gigaModel
        settingsProvider.gigaModel in availableModels -> settingsProvider.gigaModel
        else -> settingsProvider.defaultLlmModel(llmBuildProfile) ?: availableModels.first()
    }

    private fun applySelectedModel(model: GigaModel) {
        if (settingsProvider.gigaModel != model) {
            settingsProvider.gigaModel = model
            chatUseCase.updateModel(model)
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
        chatUseCase.finishCurrentConversation(TelemetryConversationEndReason.CLEAR_CONTEXT)
        chatUseCase.clearContext()
        chatUseCase.stopSpeechAndSideEffects()

        when (currentState.userExpectCloseOnX) {
            false -> {
                val currentText = currentState.displayedText
                val clearedText = getString(Res.string.status_context_cleared_hint).format(getString(Res.string.status_context_cleared))
                val lastText = if (currentText == getString(Res.string.status_context_cleared) || startTips.contains(currentText)) {
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
                        chatStartTip = startTips.randomOrNull() ?: "",
                        attachedFiles = emptyList(),
                        pendingVoiceInputDraft = null,
                        showNewChatDialog = false,
                    )
                }
            }

            true -> {
                val clearedText = getString(Res.string.status_context_cleared_default)
                setState {
                    copy(
                        displayedText = clearedText,
                        userExpectCloseOnX = false,
                        chatMessages = emptyList(),
                        chatStartTip = startTips.randomOrNull() ?: "",
                        attachedFiles = emptyList(),
                        pendingVoiceInputDraft = null,
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
        permissionsUseCase.rejectPendingSelectionDialog(
            sourceId = currentState.selectionDialog?.sourceId,
            requestId = currentState.selectionDialog?.requestId,
        )
        chatUseCase.onCleared()
        permissionsUseCase.onCleared()
    }
}
