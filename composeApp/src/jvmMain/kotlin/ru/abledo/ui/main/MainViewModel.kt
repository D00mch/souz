package ru.abledo.ui.main

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
import ru.abledo.agent.GraphBasedAgent
import ru.abledo.audio.*
import ru.abledo.db.DesktopInfoRepository
import ru.abledo.db.VectorDB
import ru.abledo.giga.GigaModel
import ru.abledo.giga.GigaRestChatAPI
import ru.abledo.giga.GigaVoiceAPI
import ru.abledo.keys.HotkeyListener
import ru.abledo.permissions.AppRelauncher
import ru.abledo.ui.BaseViewModel
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.minutes

class MainViewModel(
    override val di: DI,
) : BaseViewModel<MainState, MainEvent, MainEffect>(), DIAware {

    private val l = LoggerFactory.getLogger(MainViewModel::class.java)
    private val audioRecorder = InMemoryAudioRecorder(ActiveSoundRecorderImpl(), viewModelScope)
    private val agentRef = AtomicReference<GraphBasedAgent?>(null)
    private var permissionWatcherJob: Job? = null

    private val api: GigaRestChatAPI by di.instance()
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
            onHotKey = { viewModelScope.launch { clearContext() } }
        )

        launch { audioRecorder.logState() }
        val desktopInfoRepo = DesktopInfoRepository(GigaRestChatAPI.INSTANCE, VectorDB)
        viewModelScope.launchDbSetup(desktopInfoRepo)

        val model = System.getenv("GIGA_MODEL")?.let { envModel ->
            GigaModel.entries.firstOrNull { enumModel ->
                enumModel.name.equals(envModel, ignoreCase = true) ||
                    enumModel.alias.equals(envModel, ignoreCase = true)
            }
        } ?: GigaModel.Max

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

            val graphAgent = GraphBasedAgent(model.alias, api, desktopInfoRepo)
            agentRef.set(graphAgent)

            while (isActive) {
                runCatching {
                    userInputFlow.collect { userInput ->
                        val text = graphAgent.execute(userInput)
                        l.info(text)
                        setState { copy(displayedText = text, statusMessage = "Ответ готов") }
                        playText(text)
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
        setState { copy(statusMessage = "Ожидание горячей клавиши") }
    }

    private suspend fun clearContext() {
        agentRef.get()?.clearContext()
        when(currentState.userExpectCloseOnX) {
            false -> {
                val currentText = currentState.displayedText
                val clearedText = "Контекст очищен. Нажмите еще раз, чтобы скрыть."
                setState { copy(displayedText = clearedText, lastText = currentText, userExpectCloseOnX = true) }
            }

            true -> {
                val clearedText = "Контекст очищен"
                setState { copy(displayedText = clearedText, userExpectCloseOnX = false) }
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
        agentRef.get()?.cancelActiveJob()
        permissionWatcherJob?.cancel()
    }
}