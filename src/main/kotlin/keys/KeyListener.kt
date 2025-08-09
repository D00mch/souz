package com.dumch.keys

import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.NativeHookException
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener
import kotlin.system.exitProcess
import org.slf4j.LoggerFactory

class HotkeyListener(
    private val onPressed: (Boolean) -> Unit
) : NativeKeyListener {
    private var isAltPressed = false
    private var isHotkeyActive = false

    override fun nativeKeyPressed(e: NativeKeyEvent) {
        if (e.rawCode == 61 && e.keyCode == 56) {
            isAltPressed = true
        }

        // Check if hotkey combination is complete (Alt + 2)
        if (isAltPressed && !isHotkeyActive) {
            isHotkeyActive = true
            onPressed(true)
        }
    }

    override fun nativeKeyReleased(e: NativeKeyEvent) {
        if (e.rawCode == 61 && e.keyCode == 56) {
            isAltPressed = false
        }

        if (isHotkeyActive && !isAltPressed) {
            isHotkeyActive = false
            onPressed(false)
        }
    }

    override fun nativeKeyTyped(e: NativeKeyEvent) = Unit
}

fun main() {
    val l = LoggerFactory.getLogger("HotkeyListener")
    val hotkeyListener = HotkeyListener { pressed ->
        val msg = if (pressed) "onStart" else "onStop"
        l.info(msg)
    }

    try {
        GlobalScreen.registerNativeHook()
        GlobalScreen.addNativeKeyListener(hotkeyListener)
    } catch (e: NativeHookException) {
        l.error("Failed to register native hook: ${e.message}")
        exitProcess(1)
    }

    Thread.currentThread().join()
}
