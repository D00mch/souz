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
import ru.gigadesk.db.VectorDB
import ru.gigadesk.giga.GigaRestChatAPI
import ru.gigadesk.giga.GigaVoiceAPI
import ru.gigadesk.keys.HotkeyListener
import ru.gigadesk.keys.SelectedText
import ru.gigadesk.permissions.AppRelauncher
import ru.gigadesk.ui.BaseViewModel
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.minutes

class MainViewModel(
    override val di: DI,
) : BaseViewModel<MainState, MainEvent, MainEffect>(), DIAware {


    private val l = LoggerFactory.getLogger(MainViewModel::class.java)
    private val audioRecorder = InMemoryAudioRecorder(ActiveSoundRecorderImpl(), viewModelScope)
    private val selectedText: SelectedText by di.instance()
    private val agentRef = AtomicReference<GraphBasedAgent?>(null)
    private var permissionWatcherJob: Job? = null

    private val graphAgent by di.instance<GraphBasedAgent>()
    private val gigaVoiceAPI: GigaVoiceAPI by di.instance()

    init {
        ioLaunch { initializeAgent() }
    }

    override fun initialState(): MainState = MainState()

    override suspend fun handleEvent(event: MainEvent) {
        when (event) {
            MainEvent.StartListening -> startRecording()
            MainEvent.StopListening -> stopRecording()
            MainEvent.ClearContext -> clearContext()
            MainEvent.StopSpeech -> stopPlayText()
            MainEvent.ShowLastText -> setPreviousText()
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
        val desktopInfoRepo = DesktopInfoRepository(GigaRestChatAPI.INSTANCE, VectorDB)
        viewModelScope.launchDbSetup(desktopInfoRepo)

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

            while (isActive) {
                runCatching {
                    userInputFlow.collect { userInput ->
                        val selectedPostfix = selectedText.getOrNull()
                            ?.let { "\nThe selected text below:\n$it" }
                            ?: ""

                        val rawText = graphAgent.execute(userInput + selectedPostfix)
                        val safeText = sanitizeLlmResponse(rawText)
                        l.info(safeText)

                        setState { copy(displayedText = safeText, statusMessage = "Ответ готов") }
                        playText(safeText)
                    }
                }.onFailure { e ->
                    l.error("Agent flow terminated: ${e.message}", e)
                }
            }
        } finally {
            GlobalScreen.unregisterNativeHook()
        }
    }

    private fun CoroutineScope.launchDbSetup(repo: DesktopInfoRepository) = launch(Dispatchers.IO) {
        while (isActive) {
            repo.storeDesktopDataDaily()
            delay(5.minutes)
        }
    }

    private suspend fun startRecording() {
        if (currentState.isListening) return
        stopPlayText()
        agentRef.get()?.cancelActiveJob()
        playMacPing()
        setState { copy(isListening = true, statusMessage = "Запись запущена") }
        audioRecorder.start()
    }

    private suspend fun stopRecording() {
        if (!currentState.isListening) return
        audioRecorder.stop()
        setState { copy(isListening = false, statusMessage = "Обработка входа") }
        delay(300)
        playTextRand(speed = 120, "ok", "okey", "окей", "ок")
        setState { copy(statusMessage = MainState.randomStatusTip()) }
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
        stopPlayText()
        when(currentState.userExpectCloseOnX) {
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

    override fun onCleared() {
        super.onCleared()
        stopPlayText()
        agentRef.get()?.cancelActiveJob()
        permissionWatcherJob?.cancel()
    }

    private fun sanitizeLlmResponse(input: String): String {
        if (input.isBlank()) return ""

        var result = input

        result = result.replace("```", "\n```\n")

        while (result.contains("\n\n\n")) {
            result = result.replace("\n\n\n", "\n\n")
        }

        return result.trim()
    }

    private companion object {
        const val DEFAULT_CLEARED_TEXT = "Контекст очищен"
    }
}