package com.dumch

import com.dumch.audio.InMemoryAudioRecorder
import com.dumch.audio.playText
import com.dumch.giga.GigaAgent
import com.dumch.giga.GigaAuth
import com.dumch.giga.GigaChatAPI
import com.dumch.giga.GigaVoiceAPI
import com.dumch.keys.HotkeyListener
import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.NativeHookException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import org.slf4j.LoggerFactory

private const val AGENT_ALIAS = ""

private val l = LoggerFactory.getLogger("Main")

suspend fun main() = coroutineScope {
    val appScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val audioRecorder = InMemoryAudioRecorder(
        coroutineScope = appScope
    )
    val hotkeyListener = HotkeyListener { pressed ->
        l.info(if (pressed) "onStart" else "onStop")
        when {
            pressed -> audioRecorder.start()
            else -> audioRecorder.stop()
        }
    }
    launch { audioRecorder.logState() }
    val gigaVoiceAPI = GigaVoiceAPI(GigaAuth)
    val gigaChatAPI  = GigaChatAPI(GigaAuth)
    withNativeHook(hotkeyListener) {
        val userInputFlow = audioRecorder.audioFlow
            .onEach { l.info("\n$AGENT_ALIAS: [Received audio data: ${it.size} bytes]") }
            .catch { l.error("Error in audio flow: ${it.message}") }
            .map { audioData ->
                val resp = gigaVoiceAPI.recognize(audioData)
                l.info("Recognition response: $resp")
                resp.result.joinToString("\n")
            }

        GigaAgent.instance(userInputFlow, gigaChatAPI).run().collect { text ->
            l.info("AI text: $text")
            playText(text)
        }
    }
}

private suspend fun InMemoryAudioRecorder.logState(): Nothing {
    recordingState.collect { state ->
        when (state) {
            is InMemoryAudioRecorder.State.Starting -> l.info("Recording state: Starting audio recording...")
            is InMemoryAudioRecorder.State.Recording -> l.info("Recording state: Recording... (press Option + 2 to stop)")
            is InMemoryAudioRecorder.State.Stopping -> l.info("Recording state: Stopping recording...")
            is InMemoryAudioRecorder.State.Idle -> {
                l.info("Recording state: Idle")
            }

            is InMemoryAudioRecorder.State.Error -> l.error("Recording state: Error: ${state.message}")
        }
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
