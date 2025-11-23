package ru.abledo

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import ru.abledo.agent.GraphBasedAgent
import ru.abledo.audio.*
import ru.abledo.db.DesktopDataExtractor
import ru.abledo.db.DesktopInfoRepository
import ru.abledo.db.VectorDB
import ru.abledo.giga.*
import ru.abledo.keys.HotkeyListener
import ru.abledo.ui.LiquidGlassPanel
import ru.abledo.ui.setAppIcon
import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.NativeHookException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import org.kodein.di.compose.withDI
import org.slf4j.LoggerFactory
import ru.abledo.di.mainDiModule
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.minutes

private val l = LoggerFactory.getLogger("AI")


fun main() = application {
    withDI(mainDiModule) {
        Window(
            onCloseRequest = ::exitApplication,
            title = "TestJvmSize",
        ) {
            App()
        }
    }
}

suspend fun main2() = coroutineScope {
    println("Balance:\n${GigaRestChatAPI.INSTANCE.balance()}\n")
    setAppIcon()
    val glassPanel = LiquidGlassPanel().apply {
        showText("Готов работать")
    }
    val appScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val audioRecorder = InMemoryAudioRecorder(
        recorder = ActiveSoundRecorderImpl(),
        coroutineScope = appScope,
    )
    val agentRef = AtomicReference<GraphBasedAgent>(null)
    val hotkeyListener = HotkeyListener(
        onPressed = { pressed ->
            l.info(if (pressed) "onStart" else "onStop")
            when {
                pressed -> {
                    stopPlayText()
                    agentRef.get()?.cancelActiveJob()
                    playMacPing()
                    audioRecorder.start()
                }
                else -> {
                    audioRecorder.stop()
                    launch {
                        delay(300)
                        playTextRand(speed = 120, "ok", "okey", "окей", "ок")
                    }
                }
            }
        },
        onDoubleClick = {
            stopPlayText()
            agentRef.get()?.cancelActiveJob()
        },
        onHotKey = {
            stopPlayText()
            agentRef.get()?.clearContext()
        }
    )
    launch { audioRecorder.logState() }
    val gigaVoiceAPI = GigaVoiceAPI(GigaAuth)
    val desktopInfoRepo = DesktopInfoRepository(GigaRestChatAPI.INSTANCE, VectorDB)
    appScope.launchDbSetup(desktopInfoRepo)

    val model = System.getenv("GIGA_MODEL")?.let { envModel ->
        GigaModel.entries.firstOrNull { enumModel ->
            enumModel.name.equals(envModel, ignoreCase = true) ||
                    enumModel.alias.equals(envModel, ignoreCase = true)
        }
    } ?: GigaModel.Max
    val api = GigaRestChatAPI(GigaAuth)

    withNativeHook(hotkeyListener) {
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
                    glassPanel.showText(text)
                    playText(text)
                }
            }.onFailure { e ->
                l.error("Agent flow terminated: ${e.message}", e)
            }
        }
    }
}

/**
 * Updates data once a day.
 * Update browser history every 5 minutes.
 */
private fun CoroutineScope.launchDbSetup(repo: DesktopInfoRepository) = launch {
    repo.storeDesktopDataDaily()
    while (true) {
        delay(5.minutes)
        val browserHistory = DesktopDataExtractor.browserHistory(10)
        repo.storeDesktopInfo(browserHistory)
    }
}

private suspend fun withNativeHook(hotkeyListener: HotkeyListener, block: suspend () -> Unit) {
    try {
        GlobalScreen.registerNativeHook()
        GlobalScreen.addNativeKeyListener(hotkeyListener)
        block()
    } catch (e: NativeHookException) {
        l.error("Failed to initialize hotkey listener: ${e.message}")
    } finally {
        GlobalScreen.unregisterNativeHook()
    }
}
