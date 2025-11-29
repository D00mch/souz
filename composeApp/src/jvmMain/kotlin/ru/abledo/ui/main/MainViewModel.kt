package ru.abledo.ui.main

import androidx.lifecycle.viewModelScope
import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.NativeHookException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import org.slf4j.LoggerFactory
import ru.abledo.agent.GraphBasedAgent
import ru.abledo.audio.ActiveSoundRecorderImpl
import ru.abledo.audio.InMemoryAudioRecorder
import ru.abledo.audio.playMacPing
import ru.abledo.audio.playText
import ru.abledo.audio.playTextRand
import ru.abledo.audio.rawToOpusOgg
import ru.abledo.audio.stopPlayText
import ru.abledo.db.DesktopDataExtractor
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
import ru.abledo.tool.ToolRunBashCommand

class MainViewModel(
    override val di: DI,
) : BaseViewModel<MainState, MainEvent, MainEffect>(), DIAware {

    private val l = LoggerFactory.getLogger(MainViewModel::class.java)
    private val appScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val audioRecorder = InMemoryAudioRecorder(ActiveSoundRecorderImpl(), appScope)
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
        }
    }

    override suspend fun handleSideEffect(effect: MainEffect) {
        when (effect) {
            is MainEffect.ShowError -> l.error(effect.message)
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
        appScope.launchDbSetup(desktopInfoRepo)

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
        val clearedText = "Контекст очищен"
        setState { copy(displayedText = clearedText) }
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
        appScope.cancel()
        agentRef.get()?.cancelActiveJob()
        permissionWatcherJob?.cancel()
    }
}

private fun CoroutineScope.launchDbSetup(repo: DesktopInfoRepository) = launch {
    repo.storeDesktopDataDaily()
    while (true) {
        delay(5.minutes)
        val browserHistory = DesktopDataExtractor.browserHistory(10)
        repo.storeDesktopInfo(browserHistory)
    }
}

enum class BrowserType {
    SAFARI, CHROME, OTHER, UNKNOWN
}

fun detectDefaultBrowser(bash: ToolRunBashCommand): BrowserType {
    val cmd = "plutil -convert xml1 -o - ~/Library/Preferences/com.apple.LaunchServices/com.apple.launchservices.secure.plist"
    val xmlOutput = bash.sh(cmd)

    val bundleId = parsePlistForHttpHandler(xmlOutput) ?: "com.apple.Safari" // Если настройки нет, по умолчанию это Safari

    return when {
        bundleId.contains("chrome", ignoreCase = true) -> BrowserType.CHROME
        bundleId.contains("safari", ignoreCase = true) -> BrowserType.SAFARI
        else -> BrowserType.OTHER
    }
}

private fun parsePlistForHttpHandler(xml: String): String? {
    val dicts = xml.split("</dict>")

    for (dict in dicts) {
        if (dict.contains("<key>LSHandlerURLScheme</key>") &&
            dict.contains("<string>http</string>")) {

            val pattern = "<key>LSHandlerRoleAll</key>\\s*<string>(.*?)</string>".toRegex(RegexOption.DOT_MATCHES_ALL)
            val match = pattern.find(dict)

            if (match != null) {
                return match.groupValues[1]
            }
        }
    }
    return null
}

fun main() {
    println(detectDefaultBrowser(ToolRunBashCommand))
}
