package com.dumch

import com.dumch.audio.InMemoryAudioRecorder
import com.dumch.keys.HotkeyListener
import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.NativeHookException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

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
    withNativeHook(hotkeyListener) {
        audioRecorder.audioFlow
            .onEach { audioData ->
                println("\n$AGENT_ALIAS: [Received audio data: ${audioData.size} bytes]")
            }
            .catch { e ->
                System.err.println("Error in audio flow: ${e.message}")
            }
            .collect()
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

private fun userInputFlow(): Flow<String> = flow {
    println("\nType your message or `exit` to quit")
    while (true) {
        print("> ")
        val input = readLine() ?: break
        if (input.equals("exit", ignoreCase = true)) break
        emit(input)
    }
}
