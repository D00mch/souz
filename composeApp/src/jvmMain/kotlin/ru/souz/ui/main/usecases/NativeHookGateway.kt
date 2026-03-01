package ru.souz.ui.main.usecases

import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener

interface NativeHookGateway {
    fun registerNativeHook()
    fun addNativeKeyListener(listener: NativeKeyListener)
    fun unregisterNativeHook()
}

object JNativeHookGateway : NativeHookGateway {
    override fun registerNativeHook() {
        GlobalScreen.registerNativeHook()
    }

    override fun addNativeKeyListener(listener: NativeKeyListener) {
        GlobalScreen.addNativeKeyListener(listener)
    }

    override fun unregisterNativeHook() {
        GlobalScreen.unregisterNativeHook()
    }
}
