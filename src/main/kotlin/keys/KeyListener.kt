package com.dumch.keys

import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.NativeHookException
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener
import jdk.jfr.RecordingState
import kotlinx.coroutines.*
import kotlin.system.exitProcess

class HotkeyListener(
    private val onPressed: (Boolean) -> Unit
) : NativeKeyListener {
    private var isAltPressed = false
    private var is2Pressed = false
    private var isHotkeyActive = false

    override fun nativeKeyPressed(e: NativeKeyEvent) {
        when (e.keyCode) {
            NativeKeyEvent.VC_ALT -> isAltPressed = true
            NativeKeyEvent.VC_2 -> is2Pressed = true
        }

        // Check if hotkey combination is complete (Alt + 2)
        if (isAltPressed && is2Pressed && !isHotkeyActive) {
            isHotkeyActive = true
            onPressed(true)
        }
    }

    override fun nativeKeyReleased(e: NativeKeyEvent) {
        when (e.keyCode) {
            NativeKeyEvent.VC_ALT -> isAltPressed = false
            NativeKeyEvent.VC_2 -> is2Pressed = false
        }

        if (isHotkeyActive && (!isAltPressed || !is2Pressed)) {
            isHotkeyActive = false
            onPressed(false)
        }
    }

    override fun nativeKeyTyped(e: NativeKeyEvent) = Unit
}

fun main() {
    val hotkeyListener = HotkeyListener { pressed ->
        val msg = if (pressed) "onStart" else "onStop"
        println(msg)
    }

    try {
        GlobalScreen.registerNativeHook()
        GlobalScreen.addNativeKeyListener(hotkeyListener)
    } catch (e: NativeHookException) {
        System.err.println("Failed to register native hook: ${e.message}")
        exitProcess(1)
    }

    Thread.currentThread().join()
}
