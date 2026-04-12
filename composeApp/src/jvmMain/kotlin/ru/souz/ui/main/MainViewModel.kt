@file:OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)

package ru.souz.ui.main

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
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
import ru.souz.agent.state.AgentContext
import ru.souz.db.DesktopInfoRepository
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmBuildProfile
import ru.souz.llms.local.LocalModelDownloadState
import ru.souz.llms.local.LocalLlamaRuntime
import ru.souz.llms.local.LocalModelProfiles
import ru.souz.llms.local.LocalModelStore
import ru.souz.llms.local.downloadPromptFor
import ru.souz.ui.BaseViewModel
import ru.souz.ui.main.search.ChatMessageSearchProjection
import ru.souz.ui.main.search.ChatSearchEngine
import ru.souz.ui.main.search.ChatSearchState
import ru.souz.ui.main.usecases.ChatUseCase
import ru.souz.ui.main.usecases.MainUseCaseOutput
import ru.souz.ui.main.usecases.MainUseCases
import ru.souz.ui.main.usecases.MainUseCasesFactory
import ru.souz.ui.main.usecases.PermissionsUseCase
import ru.souz.ui.main.usecases.SpeechUseCase
import ru.souz.ui.main.usecases.ToolModifyReviewUseCase
import ru.souz.ui.main.usecases.VoiceInputUseCase
import ru.souz.service.telemetry.TelemetryConversationEndReason
import ru.souz.service.telemetry.TelemetryRequestSource
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
    private val localModelStore: LocalModelStore by di.instance()
    private val localLlamaRuntime: LocalLlamaRuntime by di.instance()
    private val mainUseCasesFactory: MainUseCasesFactory by di.instance()

    private val telegramBotController: ru.souz.service.telegram.TelegramBotController by di.instance()
    private var lastAppliedAgentId = agentFacade.activeAgentId.value

    private val useCases: MainUseCases = mainUseCasesFactory.create(ioDispatchers)
    private val chatUseCase: ChatUseCase = useCases.chat
    private val toolModifyReviewUseCase: ToolModifyReviewUseCase = useCases.toolModifyReview
    private val voiceInputUseCase: VoiceInputUseCase = useCases.voiceInput
    private val speechUseCase: SpeechUseCase = useCases.speech
    private val permissionsUseCase: PermissionsUseCase = useCases.permissions
    private val attachmentsUseCase = useCases.attachments
    private val chatSearchEngine = ChatSearchEngine()
    private var chatSearchProjections: Map<String, ChatMessageSearchProjection> = emptyMap()
    private var startTips: List<String> = emptyList()
    private var localModelDownloadJob: Job? = null
    private var localModelPreloadJob: Job? = null

    init {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { collectUseCaseOutputs() }

        viewModelScope.launch {
            startTips = getStringArray(Res.array.start_tips)
            val randomTip = startTips.randomOrNull() ?: ""
            val availableModels = settingsProvider.availableLlmModels(llmBuildProfile)
            val selectedModel = pickConfiguredOrDefaultModel(availableModels)
            val downloadPrompt = localModelStore.downloadPromptFor(selectedModel)
            if (downloadPrompt == null) {
                applySelectedModel(selectedModel)
            }

            setState {
                copy(
                    selectedModel = selectedModel.alias,
                    availableModelAliases = availableModels.map { it.alias },
                    selectedContextSize = settingsProvider.contextSize,
                    displayedText = randomTip,
                    chatStartTip = randomTip,
                    localModelDownloadPrompt = downloadPrompt,
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
            MainEvent.StopSpeech -> chatUseCase.stopAssistantOutput()
            MainEvent.UserPressStop -> chatUseCase.abortActiveRequest()
            MainEvent.ShowLastText -> setPreviousText()
            MainEvent.ToggleThinkingPanel -> setState { copy(isThinkingPanelOpen = !isThinkingPanelOpen) }
            is MainEvent.UpdateChatModel -> updateChatModel(event.model)
            MainEvent.ConfirmLocalModelDownload -> confirmLocalModelDownload()
            MainEvent.CancelLocalModelDownload -> cancelLocalModelDownload()
            is MainEvent.UpdateChatContextSize -> updateChatContextSize(event.size)
            MainEvent.PickChatAttachments -> pickChatAttachments()
            is MainEvent.AttachDroppedFiles -> addAttachedFiles(event.paths)
            is MainEvent.RemoveChatAttachment -> removeAttachedFile(event.path)
            is MainEvent.UpdateChatSearchQuery -> setStateAndReindexChatSearch {
                copy(chatSearch = chatSearchEngine.updateQuery(chatSearch, event.query))
            }
            MainEvent.SelectNextChatSearchResult -> setState {
                copy(chatSearch = chatSearchEngine.next(chatSearch))
            }
            MainEvent.SelectPreviousChatSearchResult -> setState {
                copy(chatSearch = chatSearchEngine.previous(chatSearch))
            }
            is MainEvent.OpenPath -> {
                ru.souz.ui.common.FinderService.openInFinder(event.path)
                    .onFailure { error ->
                        send(MainEffect.ShowError(error.message ?: getString(Res.string.error_failed_to_open_path)))
                    }
            }
            is MainEvent.SendChatMessage -> vmLaunch {
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

            MainEvent.RefreshSettings -> refreshSettings()
            is MainEvent.ToggleToolModifyReviewSelection ->
                toolModifyReviewUseCase.toggleSelection(
                    messageId = event.messageId,
                    itemId = event.itemId,
                )

            is MainEvent.ResolveToolModifyReview -> {
                val selectedIds = currentState.chatMessages
                    .firstOrNull { it.id == event.messageId }
                    ?.toolModifyReview
                    ?.items
                    ?.filter { it.selected }
                    ?.mapTo(linkedSetOf()) { it.id }
                    .orEmpty()
                toolModifyReviewUseCase.resolve(
                    messageId = event.messageId,
                    action = event.action,
                    selectedIds = selectedIds,
                )
            }

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

    fun chatSearchProjectionFor(messageId: String): ChatMessageSearchProjection? =
        chatSearchProjections[messageId]

    override suspend fun handleSideEffect(effect: MainEffect) {
        when (effect) {
            is MainEffect.ShowError -> l.error(effect.message)
            MainEffect.Hide -> Unit
        }
    }

    private suspend fun collectUseCaseOutputs() {
        merge(
            chatUseCase.outputs,
            toolModifyReviewUseCase.outputs,
            voiceInputUseCase.outputs,
            speechUseCase.outputs,
            permissionsUseCase.outputs,
        ).collect { output ->
            when (output) {
                is MainUseCaseOutput.State -> {
                    if (output.refreshChatSearch) {
                        setStateAndRefreshChatSearch(output.reduce)
                    } else {
                        setState(output.reduce)
                    }
                }
                is MainUseCaseOutput.Effect -> send(output.effect)
            }
        }
    }

    private suspend fun setStateAndReindexChatSearch(
        reduce: MainState.() -> MainState,
    ) {
        var projections = emptyMap<String, ChatMessageSearchProjection>()
        setState {
            val updatedState = reduce()
            projections = chatSearchEngine.ensureProjections(
                messages = updatedState.chatMessages,
                cached = chatSearchProjections,
            )
            updatedState.copy(
                chatSearch = chatSearchEngine.reindex(
                    messages = updatedState.chatMessages,
                    projections = projections,
                    search = updatedState.chatSearch,
                )
            )
        }
        chatSearchProjections = projections
    }

    private suspend fun setStateAndRefreshChatSearch(
        reduce: MainState.() -> MainState,
    ) {
        var projections = emptyMap<String, ChatMessageSearchProjection>()
        setState {
            val updatedState = reduce()
            projections = chatSearchEngine.buildProjections(updatedState.chatMessages)
            updatedState.copy(
                chatSearch = chatSearchEngine.reindex(
                    messages = updatedState.chatMessages,
                    projections = projections,
                    search = updatedState.chatSearch,
                )
            )
        }
        chatSearchProjections = projections
    }

    private fun CoroutineScope.launchDbSetup(repo: DesktopInfoRepository): Job = launch(ioDispatchers) {
        while (isActive) {
            runCatching { repo.storeDesktopDataDaily() }
                .onFailure { error ->
                    if (error !is CancellationException) {
                        l.warn("Desktop index refresh failed: {}", error.message)
                    }
                }
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
        chatUseCase.finishCurrentConversation(TelemetryConversationEndReason.NEW_CONVERSATION)
        chatUseCase.clearConversationContext()

        setStateAndRefreshChatSearch {
            copy(
                displayedText = startTips.randomOrNull() ?: "",
                statusMessage = "",
                agentActions = emptyList(),
                lastText = null,
                lastKnownAgentContext = null,
                userExpectCloseOnX = false,
                isProcessing = false,
                isAwaitingToolReview = false,
                chatMessages = emptyList(),
                chatStartTip = startTips.randomOrNull() ?: "",
                chatSessionId = chatSessionId + 1,
                attachedFiles = emptyList(),
                pendingVoiceInputDraft = null,
                showNewChatDialog = false,
                chatSearch = ChatSearchState(),
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
        if (currentState.localModelDownloadState != null) return
        val availableModels = settingsProvider.availableLlmModels(llmBuildProfile)
        val model = availableModels.firstOrNull { it.alias == modelAlias } ?: return
        val downloadPrompt = localModelStore.downloadPromptFor(model)
        if (downloadPrompt != null) {
            setState {
                copy(
                    localModelDownloadPrompt = downloadPrompt,
                    localModelDownloadState = null,
                )
            }
            return
        }
        applySelectedModel(model)
        setState { copy(selectedModel = model.alias) }
    }

    private suspend fun confirmLocalModelDownload() {
        val prompt = currentState.localModelDownloadPrompt ?: return
        localModelDownloadJob?.cancelAndJoin()
        localModelDownloadJob = viewModelScope.launch(ioDispatchers) {
            setState {
                copy(
                    localModelDownloadPrompt = null,
                    localModelDownloadState = LocalModelDownloadState(prompt),
                )
            }

            runCatching {
                localModelStore.downloadRequiredAssets(prompt.profile) { progress ->
                    setState {
                        copy(localModelDownloadState = LocalModelDownloadState(prompt, progress))
                    }
                }
            }.onSuccess {
                applySelectedModel(prompt.model)
                setState {
                    copy(
                        selectedModel = prompt.model.alias,
                        localModelDownloadState = null,
                    )
                }
            }.onFailure { error ->
                setState { copy(localModelDownloadState = null) }
                if (error is CancellationException) {
                    return@onFailure
                }
                val message = error.message ?: getString(Res.string.local_model_download_failed)
                send(MainEffect.ShowError(message))
            }

            localModelDownloadJob = null
        }
    }

    private suspend fun cancelLocalModelDownload() {
        val hadActiveDownload = currentState.localModelDownloadState != null
        localModelDownloadJob?.cancelAndJoin()
        localModelDownloadJob = null
        setState {
            copy(
                localModelDownloadPrompt = null,
                localModelDownloadState = null,
            )
        }
        if (hadActiveDownload) {
            send(MainEffect.ShowError(getString(Res.string.local_model_download_cancelled)))
        }
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
        val downloadPrompt = localModelStore.downloadPromptFor(selectedModel)
        if (downloadPrompt == null) {
            applySelectedModel(selectedModel)
        }

        setState {
            copy(
                selectedModel = selectedModel.alias,
                availableModelAliases = availableModels.map { it.alias },
                selectedContextSize = settingsProvider.contextSize,
                localModelDownloadPrompt = downloadPrompt,
            )
        }
    }

    private fun pickConfiguredOrDefaultModel(availableModels: List<LLMModel>) = when {
        availableModels.isEmpty() -> settingsProvider.gigaModel
        settingsProvider.gigaModel in availableModels -> settingsProvider.gigaModel
        else -> settingsProvider.defaultLlmModel(llmBuildProfile) ?: availableModels.first()
    }

    private fun applySelectedModel(model: LLMModel) {
        val previousEmbeddingsModel = settingsProvider.embeddingsModel
        if (settingsProvider.gigaModel != model) {
            settingsProvider.gigaModel = model
            chatUseCase.updateModel(model)
        }
        val effectiveEmbeddingsModel = settingsProvider.embeddingsModel
        if (previousEmbeddingsModel != effectiveEmbeddingsModel) {
            scheduleDesktopIndexRebuild()
        }
        scheduleLocalModelPreload(model)
    }

    private fun scheduleDesktopIndexRebuild() {
        viewModelScope.launch(ioDispatchers) {
            runCatching { desktopInfoRepository.rebuildIndexNow() }
                .onFailure { error ->
                    l.warn("Desktop index rebuild failed: {}", error.message)
                }
        }
    }

    private fun scheduleLocalModelPreload(model: LLMModel) {
        if (!LocalModelProfiles.isLocalModelAlias(model.alias)) {
            localModelPreloadJob?.cancel()
            localModelPreloadJob = null
            return
        }
        localModelPreloadJob?.cancel()
        localModelPreloadJob = viewModelScope.launch(ioDispatchers) {
            runCatching { localLlamaRuntime.preload(model.alias) }
                .onFailure { error ->
                    if (error !is CancellationException) {
                        l.warn("Local model preload failed for {}: {}", model.alias, error.message)
                    }
                }
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
        chatUseCase.clearConversationContext()

        when (currentState.userExpectCloseOnX) {
            false -> {
                val currentText = currentState.displayedText
                val clearedText = getString(Res.string.status_context_cleared_hint).format(getString(Res.string.status_context_cleared))
                val lastText = if (currentText == getString(Res.string.status_context_cleared) || startTips.contains(currentText)) {
                    null
                } else {
                    currentText
                }
                setStateAndRefreshChatSearch {
                    copy(
                        displayedText = clearedText,
                        lastText = lastText,
                        lastKnownAgentContext = lastKnownAgentContext ?: currentState.lastKnownAgentContext,
                        userExpectCloseOnX = true,
                        isAwaitingToolReview = false,
                        chatMessages = emptyList(),
                        agentActions = emptyList(),
                        chatStartTip = startTips.randomOrNull() ?: "",
                        attachedFiles = emptyList(),
                        pendingVoiceInputDraft = null,
                        showNewChatDialog = false,
                        chatSearch = ChatSearchState(),
                    )
                }
            }

            true -> {
                val clearedText = getString(Res.string.status_context_cleared_default)
                setStateAndRefreshChatSearch {
                    copy(
                        displayedText = clearedText,
                        userExpectCloseOnX = false,
                        chatMessages = emptyList(),
                        agentActions = emptyList(),
                        chatStartTip = startTips.randomOrNull() ?: "",
                        attachedFiles = emptyList(),
                        pendingVoiceInputDraft = null,
                        showNewChatDialog = false,
                        chatSearch = ChatSearchState(),
                    )
                }
                send(MainEffect.Hide)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        localModelPreloadJob?.cancel()
        permissionsUseCase.rejectPendingPermissionRequest(currentState.toolPermissionDialog?.requestId)
        permissionsUseCase.rejectPendingSelectionDialog(
            sourceId = currentState.selectionDialog?.sourceId,
            requestId = currentState.selectionDialog?.requestId,
        )
        chatUseCase.onCleared()
        permissionsUseCase.onCleared()
    }
}
