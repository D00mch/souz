@file:OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)

package ru.gigadesk.ui.main

import androidx.lifecycle.viewModelScope
import com.github.kwhat.jnativehook.GlobalScreen
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import org.slf4j.LoggerFactory
import ru.gigadesk.agent.GraphBasedAgent
import ru.gigadesk.agent.engine.AgentContext
import ru.gigadesk.audio.*
import ru.gigadesk.db.DesktopInfoRepository
import ru.gigadesk.db.SettingsProvider
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.flow.retryWhen
import ru.gigadesk.giga.GigaVoiceAPI
import ru.gigadesk.giga.GigaModel
import ru.gigadesk.keys.HotkeyListener
import ru.gigadesk.permissions.AppRelauncher
import ru.gigadesk.tool.ToolPermissionBroker
import ru.gigadesk.ui.BaseViewModel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.time.Duration.Companion.minutes

class MainViewModel(
    override val di: DI,
) : BaseViewModel<MainState, MainEvent, MainEffect>(), DIAware {

    private val l = LoggerFactory.getLogger(MainViewModel::class.java)
    private val audioRecorder = InMemoryAudioRecorder(ActiveSoundRecorderImpl(), viewModelScope)
    private val agentRef = AtomicReference<GraphBasedAgent?>(null)
    private var permissionWatcherJob: Job? = null

    private val graphAgent by di.instance<GraphBasedAgent>()
    private val gigaVoiceAPI: GigaVoiceAPI by di.instance()
    private val desktopInfoRepository: DesktopInfoRepository by di.instance()
    private val settingsProvider: SettingsProvider by di.instance()
    private var onboardingSpeechStartedAt: Long? = null

    private val say: Say by di.instance()
    private val taskSideEffectJobs = ArrayList<Job>()
    private val activeChatRequestId = AtomicLong(0L)

    private val toolPermissionBroker: ToolPermissionBroker by di.instance()

    init {
        viewModelScope.launch {
            setState {
                copy(
                    selectedModel = settingsProvider.gigaModel.alias,
                    selectedContextSize = settingsProvider.contextSize
                )
            }
        }
        viewModelScope.launch { runOnboarding() }
        ioLaunch { initializeAgent() }
        vmLaunch { observeSpeakingState() }
        vmLaunch { observeToolPermissionRequests() }
    }

    override fun initialState(): MainState = MainState()

    override suspend fun handleEvent(event: MainEvent) {
        when (event) {
            MainEvent.StartListening -> startRecording()
            MainEvent.StopListening -> stopRecording()
            MainEvent.RequestNewConversation -> requestNewConversation()
            MainEvent.ConfirmNewConversation -> confirmNewConversation()
            MainEvent.DismissNewConversationDialog -> dismissNewConversationDialog()
            MainEvent.ClearContext -> clearContext()
            MainEvent.StopSpeech -> killTaskSideEffectJobs()
            MainEvent.StopAgentJob -> agentRef.get()?.cancelActiveJob()
            MainEvent.ShowLastText -> setPreviousText()
            MainEvent.ToggleThinkingPanel -> setState { copy(isThinkingPanelOpen = !isThinkingPanelOpen) }
            is MainEvent.UpdateChatInput -> setState { copy(chatInputText = event.text) }
            is MainEvent.UpdateChatModel -> updateChatModel(event.model)
            is MainEvent.UpdateChatContextSize -> updateChatContextSize(event.size)
            MainEvent.SendChatMessage -> vmLaunch { sendChatMessage(false, currentState.chatInputText.text) }
            MainEvent.RefreshSettings -> refreshSettings()
            MainEvent.ApproveToolPermission -> resolveToolPermission(approved = true)
            MainEvent.RejectToolPermission -> resolveToolPermission(approved = false)
        }
    }

    override suspend fun handleSideEffect(effect: MainEffect) {
        when (effect) {
            is MainEffect.ShowError -> l.error(effect.message)
            MainEffect.Hide -> Unit
        }
    }

    private suspend fun initializeAgent() = coroutineScope {
        val hotkeyListener = HotkeyListener(
            onPressed = { pressed ->
                l.info(if (pressed) "onStart" else "onStop")
                when {
                    pressed -> viewModelScope.launch { startRecording() }
                    else -> viewModelScope.launch { stopRecording() }
                }
            },
            onDoubleClick = { agentRef.get()?.cancelActiveJob() },
        )

        launch { audioRecorder.logState() }
        viewModelScope.launchDbSetup(desktopInfoRepository)

        if (!registerNativeHook()) {
            handleMissingInputMonitoringPermission()
            return@coroutineScope
        }

        try {
            GlobalScreen.addNativeKeyListener(hotkeyListener)
            val userInputFlow = audioRecorder.audioFlow
                .onEach { l.debug("[Received audio data: ${it.size} bytes]") }
                .catch { l.error("Error in audio flow: ${it.message}") }
                .mapLatest { audioData ->
                    val encodedAudio = rawToOpusOgg(rawData = audioData)
                    l.debug("[Sending audio data: ${encodedAudio.size} bytes]")
                    val resp = gigaVoiceAPI.recognize(encodedAudio)
                    l.info("Recognition response: $resp")
                    resp.result.joinToString("\n").trim()
                }
                .onEach(::onTextRecognizeSideEffects)
                .filter { it.isNotBlank() }

            agentRef.set(graphAgent)

            launch {
                graphAgent.currentContext.collect { ctx ->
                     setState { copy(agentHistory = ctx.history) }
                }
            }

            userInputFlow.retryWhen { cause, attempt ->
                if (cause is CancellationException) return@retryWhen false
                l.error("Agent flow failed, attempt $attempt, cause: ${cause.message}", cause)
                setState { copy(isProcessing = false, statusMessage = "Ошибка: ${cause.message}") }
                delay(1000L)
                true
            }.collectLatest { userInput ->
                withContext(Dispatchers.Main) {
                    sendChatMessage(
                        isVoice = true,
                        chatMessage = userInput
                    )
                }
            }
        } finally {
            GlobalScreen.unregisterNativeHook()
        }
    }

    private suspend fun onTextRecognizeSideEffects(recognizedText: String) {
        if (recognizedText.isNotBlank()) return
        val msg = "Речь не распознана"
        ioLaunch { say.queue(msg) }
        setState { copy(statusMessage = msg, isProcessing = false) }
    }

    private fun CoroutineScope.launchDbSetup(repo: DesktopInfoRepository) = launch(Dispatchers.IO) {
        while (isActive) {
            repo.storeDesktopDataDaily()
            delay(5.minutes)
        }
    }

    private suspend fun startRecording() {
        if (currentState.isListening) return
        killTaskSideEffectJobs()
        l.info("About to agent's cancelActiveJob")
        agentRef.get()?.cancelActiveJob()
        ioLaunch { say.playMacPing() }
        setState { copy(isListening = true, statusMessage = "Запись запущена") }
        audioRecorder.start()
    }

    private suspend fun stopRecording() {
        if (!currentState.isListening) return
        audioRecorder.stop()
        setState { copy(isListening = false, statusMessage = "Обработка входа") }
        delay(300)
        ioLaunch { say.playTextRand(speed = 120, "ok", "okey", "окей", "ок") }
    }

    private suspend fun sendChatMessage(
        isVoice: Boolean,
        chatMessage: String,
    ) {
        killTaskSideEffectJobs()
        agentRef.get()?.cancelActiveJob()
        val userText = chatMessage.trim()
        if (userText.isEmpty()) return
        val requestId = activeChatRequestId.incrementAndGet()

        val userMessage = ChatMessage(
            text = userText,
            isUser = true,
            isVoice = isVoice
        )
        setState {
            copy(
                chatMessages = chatMessages + userMessage,
                chatStartTip = "",
                chatInputText = TextFieldValue(""),
                isProcessing = true,
                statusMessage = ""
            )
        }

        val newLastMessage = ChatMessage(
            text = "",
            isUser = false,
            isVoice = isVoice
        )
        var sideEffectsJob: Job? = null

        try {
            sideEffectsJob = subscribeOnTaskSideEffects(newLastMessage)
            l.info("About to execute agent with user input $userText")
            val response = ioAsync {
                graphAgent.execute(userText)
            }

            val botMessage = newLastMessage.copy(text = response.await())
            if (activeChatRequestId.get() != requestId) {
                l.info("Skipping stale chat response for request {}", requestId)
                return
            }
            l.info("Agent response set")
            setState {
                copy(
                    chatMessages = if (chatMessages.last().id == botMessage.id) {
                        chatMessages.mapLast { botMessage }
                    } else {
                        chatMessages + botMessage
                    },
                    isProcessing = false
                )
            }
            if (isVoice && !settingsProvider.useStreaming) {
                say.queue(prepareTextForSpeech(botMessage.text))
            }
        } catch (e: CancellationException) {
            l.info("Chat message cancelled: ${e.message}")
            val isCurrentRequest = activeChatRequestId.get() == requestId
            withContext(NonCancellable) {
                setState {
                    val idsToDrop = arrayOf(userMessage.id, newLastMessage.id)
                    copy(
                        chatMessages = chatMessages.filterNot { it.id in idsToDrop },
                        isProcessing = if (isCurrentRequest) false else isProcessing
                    )
                }
            }
        } catch (e: Exception) {
            if (activeChatRequestId.get() != requestId) {
                l.info("Ignoring stale chat failure for request {}: {}", requestId, e.message)
                return
            }
            l.error("Chat message failed: ${e.message}", e)
            val errorMessage = ChatMessage(
                text = "Ошибка: ${e.message}",
                isUser = false,
                isVoice = isVoice
            )
            setState {
                copy(
                    chatMessages = chatMessages + errorMessage,
                    isProcessing = false
                )
            }
        } finally {
            sideEffectsJob?.cancel()
            sideEffectsJob?.let { taskSideEffectJobs.remove(it) }
        }
    }

    private fun subscribeOnTaskSideEffects(msg: ChatMessage): Job {
        val job = viewModelScope.launch {
            val isCodeBlockStarted = AtomicBoolean(false)
            graphAgent.sideEffects.collect { text ->
                setState {
                    val newHistory = if (msg.id == currentState.chatMessages.lastOrNull()?.id) {
                        currentState.chatMessages.mapLast { last -> last.copy(text = last.text + text) }
                    } else {
                        currentState.chatMessages + msg.copy(text = text)
                    }
                    copy(chatMessages = newHistory)
                }
                if (!msg.isVoice) return@collect
                if (text.contains(CODE_BLOCK)) {
                    isCodeBlockStarted.set(!isCodeBlockStarted.get())
                    if (isCodeBlockStarted.get()) {
                        say.queue(prepareTextForSpeech(text.substringBefore(CODE_BLOCK)))
                    }
                }
                if (!isCodeBlockStarted.get()) {
                    say.queue(prepareTextForSpeech(text.substringAfter(CODE_BLOCK)))
                }
            }
        }
        taskSideEffectJobs.add(job)
        return job
    }

    private inline fun <T> List<T>.mapLast(transform: (T) -> T): List<T> =
        mapIndexed { i, v -> if (i == lastIndex) transform(v) else v }

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
        killTaskSideEffectJobs()
        agentRef.get()?.clearContext()
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
                showNewChatDialog = false
            )
        }
    }

    private suspend fun updateChatModel(modelAlias: String) {
        val model = GigaModel.entries.firstOrNull { it.alias == modelAlias } ?: return
        settingsProvider.gigaModel = model
        graphAgent.updateModel(model)
        setState { copy(selectedModel = model.alias) }
    }

    private suspend fun updateChatContextSize(size: Int) {
        if (size <= 0) return
        settingsProvider.contextSize = size
        graphAgent.updateContextSize(size)
        setState { copy(selectedContextSize = size) }
    }

    private suspend fun refreshSettings() {
        setState {
            copy(
                selectedModel = settingsProvider.gigaModel.alias,
                selectedContextSize = settingsProvider.contextSize
            )
        }
    }

    private suspend fun observeSpeakingState() {
        say.isSpeaking.collect { isSpeaking ->
            setState {
                val userAskWithVoice = chatMessages.lastOrNull()?.isVoice == true
                copy(isSpeaking = isSpeaking && userAskWithVoice)
            }
        }
    }

    private suspend fun observeToolPermissionRequests() {
        toolPermissionBroker.requests.collect { request ->
            setState {
                copy(
                    toolPermissionDialog = ToolPermissionDialogData(
                        requestId = request.id,
                        description = request.description,
                        params = request.params.toSortedMap(),
                    )
                )
            }
        }
    }

    private suspend fun resolveToolPermission(approved: Boolean) {
        val requestId = currentState.toolPermissionDialog?.requestId ?: return
        toolPermissionBroker.resolve(requestId, approved)
        setState { copy(toolPermissionDialog = null) }
    }

    private suspend fun setPreviousText() {
        currentState.lastKnownAgentContext?.let { ctx ->
            agentRef.get()?.setContext(ctx)
        }
        val prevText = currentState.lastText ?: return
        setState { copy(displayedText = prevText, lastText = null, userExpectCloseOnX = false) }
    }

    private suspend fun clearContext() {
        val lastKnownAgentContext: AgentContext<String>? = agentRef.get()?.currentContext?.value
        agentRef.get()?.clearContext()
        killTaskSideEffectJobs()
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
                        showNewChatDialog = false
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
                        showNewChatDialog = false
                    )
                }
                send(MainEffect.Hide)
            }
        }
    }

    private fun registerNativeHook(): Boolean = runCatching {
        GlobalScreen.registerNativeHook()
        true
    }.getOrElse { e ->
        l.error("Failed to initialize hotkey listener: ${e.message}")
        false
    }

    private fun handleMissingInputMonitoringPermission() {
        permissionWatcherJob?.cancel()
        permissionWatcherJob = viewModelScope.launch {
            val startAt = onboardingSpeechStartedAt
            if (startAt != null) {
                val elapsed = System.currentTimeMillis() - startAt
                val waitMs = max(0, ONBOARDING_PERMISSION_DELAY_MS - elapsed)
                if (waitMs > 0) {
                    delay(waitMs)
                }
            }
            setState {
                copy(
                    statusMessage = "Разрешите доступ к мониторингу ввода в настройках macOS — " +
                            "после подтверждения приложение перезапустится автоматически"
                )
            }

            while (isActive) {
                delay(4_000)
                if (canRegisterNativeHookNow()) {
                    l.info("Input monitoring permission granted, relaunching application")
                    AppRelauncher.relaunch()
                }
            }
        }
    }

    private fun canRegisterNativeHookNow(): Boolean = runCatching {
        GlobalScreen.registerNativeHook()
        GlobalScreen.unregisterNativeHook()
        true
    }.getOrElse { false }

    private suspend fun runOnboarding() {
        if (!settingsProvider.needsOnboarding) return
        settingsProvider.needsOnboarding = false
        setState { copy(displayedText = ONBOARDING_DISPLAY_TEXT) }
        val onboardingSpeech = prepareTextForSpeech(ONBOARDING_SPEECH_TEXT)
        onboardingSpeechStartedAt = System.currentTimeMillis()
        ioLaunch {
            say.queue(onboardingSpeech)
        }
    }

    override fun onCleared() {
        super.onCleared()
        currentState.toolPermissionDialog?.requestId?.let { requestId ->
            toolPermissionBroker.resolve(requestId, approved = false)
        }
        killTaskSideEffectJobs()
        say.clearQueue()
        agentRef.get()?.cancelActiveJob()
        permissionWatcherJob?.cancel()
    }

    private fun prepareTextForSpeech(text: String): String {
        var result = text.replace(Regex("$CODE_BLOCK[\\s\\S]*?$CODE_BLOCK"), "")
        result = result.replace(Regex("`[^`]+`"), "")
        result = result.replace(Regex("[\"«»„“”]"), "")
        result = result.replace(Regex("[*#]"), "")
        result = result.replace(Regex("\\s+"), " ")

        return result.trim()
    }

    /** Stop the current voice process, rm the queue */
    private fun killTaskSideEffectJobs() {
        say.clearQueue()
        taskSideEffectJobs.forEach { it.cancel() }
        taskSideEffectJobs.clear()
    }

    private companion object {
        const val CODE_BLOCK = "```"
        const val DEFAULT_CLEARED_TEXT = "Контекст очищен"
        const val ONBOARDING_PERMISSION_DELAY_MS = 100000
        const val ONBOARDING_SPEECH_TEXT = "Привет! Я ГигаДэ́ск! умный помощник на твоем компьютере... " +
            "Сейчас я попрошу доступы к приложениям, системе и файлам, чтобы работать корректно... " +
            "Я умею пользоваться браузером, работать с почтой и календарем, работать с файлами на вашем ПК, " +
            "объяснить и переписать выделенный текст, открывать приложения, создавать заметки, отвечать на вопросы, " +
            "строить графики, диаграммы на основе данных."
        val ONBOARDING_DISPLAY_TEXT = """
            Привет! Я GigaDesk, умный помощник на твоем компьютере.
            Сейчас я попрошу доступы к приложениям, системе и файлам, чтобы работать корректно.
            Я умею:
            - Пользоваться браузером
            - Работать с почтой и календарем
            - Работать с файлами на вашем ПК
            - Объяснить и переписать выделенный текст
            - Открывать приложения, создавать заметки, отвечать на вопросы
            - Строить графики, диаграммы на основе данных
            
            
            Для запуска голосового ввода - зажми правый opt(alt)
            Для очистки контекста беседы - нажми X
            Для скрытия окна - нажми Х два раза
        """.trimIndent()
    }
}
