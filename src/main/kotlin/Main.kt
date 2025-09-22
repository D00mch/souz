package com.dumch

import com.dumch.anthropic.AnthropicChatAPI
import com.dumch.audio.*
import com.dumch.db.DesktopDataExtractor
import com.dumch.db.DesktopInfoRepository
import com.dumch.db.VectorDB
import com.dumch.giga.*
import com.dumch.keys.HotkeyListener
import com.dumch.ui.LiquidGlassPanel
import com.dumch.ui.setAppIcon
import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.NativeHookException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.minutes

private val l = LoggerFactory.getLogger("AI")


suspend fun main() = coroutineScope {
    println("Balance:\n${GigaRestChatAPI.INSTANCE.balance()}\n")
    setAppIcon()
    val glassPanel = LiquidGlassPanel()
    val appScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val audioRecorder = InMemoryAudioRecorder(
        recorder = ActiveSoundRecorderImpl(),
        coroutineScope = appScope,
    )
    val agentRef = AtomicReference<GigaAgent?>(null)
    val hotkeyListener = HotkeyListener(
        onPressed = { pressed ->
            l.info(if (pressed) "onStart" else "onStop")
            when {
                pressed -> {
                    stopPlayText()
                    agentRef.get()?.stop()
                    playMacPing()
                    audioRecorder.start()
                    glassPanel.hide()
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
            agentRef.get()?.stop()
        },
        onHotKey = {
            stopPlayText()
            agentRef.get()?.requestCleanUp()
        }
    )
    launch { audioRecorder.logState() }
    val gigaVoiceAPI = GigaVoiceAPI(GigaAuth)
    val desktopInfoRepo = DesktopInfoRepository(GigaRestChatAPI.INSTANCE, VectorDB)
    appScope.launchDbSetup(desktopInfoRepo)
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

        val model = System.getenv("GIGA_MODEL")?.let { envModel ->
            GigaModel.entries.firstOrNull { enumModel ->
                 enumModel.name.equals(envModel, ignoreCase = true) ||
                         enumModel.alias.equals(envModel, ignoreCase = true)
            }
        } ?: GigaModel.Max

        while (isActive) {
            val api = AnthropicChatAPI(GigaRestChatAPI.INSTANCE)
            val agent = GigaAgent.instance(userInputFlow, api, desktopInfoRepo, model = model)
            agentRef.set(agent)
            runCatching {
                agent.run().collect { text ->
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
