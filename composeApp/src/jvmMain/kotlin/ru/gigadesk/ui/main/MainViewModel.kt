@file:OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)

package ru.gigadesk.ui.main

import androidx.lifecycle.viewModelScope
import com.github.kwhat.jnativehook.GlobalScreen
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
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
import ru.gigadesk.giga.GigaVoiceAPI
import ru.gigadesk.keys.HotkeyListener
import ru.gigadesk.permissions.AppRelauncher
import ru.gigadesk.ui.BaseViewModel
import java.util.concurrent.atomic.AtomicBoolean
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

    init {
        viewModelScope.launch { runOnboarding() }
        ioLaunch { initializeAgent() }
    }

    override fun initialState(): MainState = MainState()

    override suspend fun handleEvent(event: MainEvent) {
        when (event) {
            MainEvent.StartListening -> startRecording()
            MainEvent.StopListening -> stopRecording()
            MainEvent.ClearContext -> clearContext()
            MainEvent.StopSpeech -> killTaskSideEffectJobs()
            MainEvent.ShowLastText -> setPreviousText()
            MainEvent.ToggleThinkingPanel -> setState { copy(isThinkingPanelOpen = !isThinkingPanelOpen) }
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
                .map { audioData -> rawToOpusOgg(rawData = audioData) }
                .onEach { l.debug("[Sending audio data: ${it.size} bytes]") }
                .map { audioData ->
                    val resp = gigaVoiceAPI.recognize(audioData)
                    l.info("Recognition response: $resp")
                    resp.result.joinToString("\n")
                }

            agentRef.set(graphAgent)

            launch {
                graphAgent.currentContext.collect { ctx ->
                     setState { copy(agentHistory = ctx.history) }
                }
            }

            while (isActive) {
                runCatching {
                    userInputFlow.collect { userInput ->
                        subscribeOnTaskSideEffects(userInput)
                        val rawText = graphAgent.execute(userInput)
                        l.info(rawText)
                        setState { copy(displayedText = rawText, statusMessage = "Ответ готов", isProcessing = false) }
                        if (!settingsProvider.useGrpc) say.queue(prepareTextForSpeech(rawText))
                    }
                }.onFailure { e ->
                    l.error("Agent flow terminated: ${e.message}", e)
                    setState { copy(isProcessing = false, statusMessage = "Ошибка: ${e.message}") }
                }
            }
        } finally {
            GlobalScreen.unregisterNativeHook()
        }
    }

    private fun subscribeOnTaskSideEffects(userInput: String) {
        val job = viewModelScope.launch {
            setState { copy(displayedText = userInput) }
            val isCodeBlockStarted = AtomicBoolean(false)
            graphAgent.sideEffects.collect { text ->
                setState {
                    val prevText = if (userInput == currentState.displayedText) "" else currentState.displayedText
                    copy(displayedText = prevText + text)
                }
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
        viewModelScope.launch { taskSideEffectJobs.add(job) }
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
        agentRef.get()?.cancelActiveJob()
        ioLaunch { say.playMacPing() }
        setState { copy(isListening = true, statusMessage = "Запись запущена") }
        audioRecorder.start()
    }

    private suspend fun stopRecording() {
        if (!currentState.isListening) return
        audioRecorder.stop()
        setState { copy(isListening = false, statusMessage = "Обработка входа", isProcessing = true) }
        delay(300)
        ioLaunch { say.playTextRand(speed = 120, "ok", "okey", "окей", "ок") }
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
                        userExpectCloseOnX = true
                    )
                }
            }

            true -> {
                setState { copy(displayedText = DEFAULT_CLEARED_TEXT, userExpectCloseOnX = false) }
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
        viewModelScope.launch(Dispatchers.IO) {
            say.queue(onboardingSpeech)
        }
    }

    override fun onCleared() {
        super.onCleared()
        say.clearQueue()
        killTaskSideEffectJobs()
        agentRef.get()?.cancelActiveJob()
        permissionWatcherJob?.cancel()
    }

    private fun prepareTextForSpeech(text: String): String {
        var result = text.replace(Regex("$CODE_BLOCK[\\s\\S]*?$CODE_BLOCK"), "")
        //result = result.replace(Regex("`[^`]+`"), "")
        result = result.replace(Regex("[\"«»„“”]"), "")
        result = result.replace(Regex("[*#]"), "")
        result = result.replace(Regex("\\s+"), " ")

        return result.trim()
    }

    /** Stop the current voice process, rm the queue */
    private fun killTaskSideEffectJobs() {
        viewModelScope.launch {
            say.clearQueue()
            taskSideEffectJobs.forEach { it.cancel() }
            taskSideEffectJobs.clear()
        }
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
            
            
            Для запуска голосового ввода - зажми правый cmd(alt)
            Для очистки контекста беседы - нажми X
            Для скрытия окна - нажми Х два раза
        """.trimIndent()
    }
}
