package com.dumch

import com.dumch.audio.InMemoryAudioRecorder
import com.dumch.audio.playText
import com.dumch.giga.GigaAgent
import com.dumch.giga.GigaAuth
import com.dumch.giga.GigaChatAPI
import com.dumch.audio.WhisperJniRecognizer
import com.dumch.keys.HotkeyListener
import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.NativeHookException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

private const val AGENT_ALIAS = ""

suspend fun main() = coroutineScope {
    val appScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val audioRecorder = InMemoryAudioRecorder(
        coroutineScope = appScope
    )
    val hotkeyListener = HotkeyListener { pressed ->
        println(if (pressed) "onStart" else "onStop")
        when {
            pressed -> audioRecorder.start()
            else -> audioRecorder.stop()
        }
    }
    launch { audioRecorder.logState() }
    val gigaChatAPI  = GigaChatAPI(GigaAuth)
    withNativeHook(hotkeyListener) {
        val userInputFlow = audioRecorder.audioFlow
            .onEach { println("\n$AGENT_ALIAS: [Received audio data: ${it.size} bytes]") }
            .catch { System.err.println("Error in audio flow: ${it.message}") }
            .map { audioData ->
                val text = WhisperJniRecognizer.recognize(audioData)
                println("Recognition result: $text")
                text
            }

        GigaAgent.instance(userInputFlow, gigaChatAPI).run().collect { text ->
            print("AI text: $text")
            playText(text)
        }
    }
}

private suspend fun InMemoryAudioRecorder.logState(): Nothing {
    recordingState.collect { state ->
        when (state) {
            is InMemoryAudioRecorder.State.Starting -> println("Recording state: Starting audio recording...")
            is InMemoryAudioRecorder.State.Recording -> println("Recording state: Recording... (press Option + 2 to stop)")
            is InMemoryAudioRecorder.State.Stopping -> println("Recording state: Stopping recording...")
            is InMemoryAudioRecorder.State.Idle -> {
                println("Recording state: Idle")
            }

            is InMemoryAudioRecorder.State.Error -> println("Recording state: Error: ${state.message}")
        }
    }
}

private suspend fun withNativeHook(hotkeyListener: HotkeyListener, block: suspend () -> Unit) {
    try {
        GlobalScreen.registerNativeHook()
        GlobalScreen.addNativeKeyListener(hotkeyListener)
        block()
    } catch (e: NativeHookException) {
        System.err.println("Failed to initialize hotkey listener: ${e.message}")
    } finally {
        GlobalScreen.unregisterNativeHook()
    }
}
