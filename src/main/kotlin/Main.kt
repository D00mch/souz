package com.dumch

import com.dumch.audio.ActiveSoundActiveSoundRecorder
import com.dumch.audio.InMemoryAudioRecorder
import com.dumch.audio.playMacPing
import com.dumch.audio.playText
import com.dumch.audio.playTextRand
import com.dumch.audio.stopPlayText
import com.dumch.audio.rawToOpusOgg
import com.dumch.db.VectorDB
import com.dumch.db.DesktopInfoRepository
import com.dumch.giga.GigaAgent
import com.dumch.giga.GigaAuth
import com.dumch.giga.GigaGRPCChatApi
import com.dumch.giga.GigaModel
import com.dumch.giga.GigaRestChatAPI
import com.dumch.giga.GigaVoiceAPI
import com.dumch.keys.HotkeyListener
import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.NativeHookException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference

private val l = LoggerFactory.getLogger("AI")

suspend fun main() = coroutineScope {
    val appScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val audioRecorder = InMemoryAudioRecorder(
        recorder = ActiveSoundActiveSoundRecorder(),
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
                }
                else -> {
                    audioRecorder.stop()
                    launch {
                        delay(300)
                        playTextRand(120, "ща сделаю", "поехали", "ну что ж, приступим", "опять работать")
                    }
                }
            }
        },
        onDoubleClick = {
            stopPlayText()
            agentRef.get()?.stop()
        }
    )
    launch { audioRecorder.logState() }
    val gigaVoiceAPI = GigaVoiceAPI(GigaAuth)
    val desktopInfoRepo = DesktopInfoRepository(GigaRestChatAPI.INSTANCE, VectorDB)
    appScope.launchDbSetup(desktopInfoRepo)
    withNativeHook(hotkeyListener) {
        val userInputFlow = audioRecorder.audioFlow
            .onEach { l.info("[Received audio data: ${it.size} bytes]") }
            .catch { l.error("Error in audio flow: ${it.message}") }
            .map { audioData -> rawToOpusOgg(rawData = audioData) }
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
            val agent = GigaAgent.instance(userInputFlow, GigaGRPCChatApi.INSTANCE, desktopInfoRepo, model = model)
            agentRef.set(agent)
            runCatching {
                agent.run().collect { text ->
                    l.info(text)
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
 */
private fun CoroutineScope.launchDbSetup(repo: DesktopInfoRepository) = launch {
    repo.storeDesktopDataDaily()
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
