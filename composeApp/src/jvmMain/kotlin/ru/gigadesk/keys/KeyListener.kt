package ru.gigadesk.keys

import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.NativeHookException
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener
import kotlin.system.exitProcess
import org.slf4j.LoggerFactory
import kotlinx.coroutines.*

class HotkeyListener(
    private val onPressed: (Boolean) -> Unit,
    private val onDoubleClick: () -> Unit,
) : NativeKeyListener {
    private var isAltPressed = false
    private var isHotkeyActive = false
    private var pressTime = 0L
    private var lastClickTime = 0L
    private val holdThreshold = 200L
    private val doubleClickThreshold = 700L
    private val scope = CoroutineScope(Dispatchers.Default)
    companion object {
        private const val OPTION_RAW_CODE = 61
    }

    override fun nativeKeyPressed(e: NativeKeyEvent) {
        if (e.rawCode == OPTION_RAW_CODE && e.keyCode == VK.SHIFT) {
            pressTime = System.currentTimeMillis()
            isAltPressed = true
            scope.launch {
                delay(holdThreshold)
                if (isAltPressed && !isHotkeyActive) {
                    isHotkeyActive = true
                    onPressed(true)
                }
            }
        }
    }

    override fun nativeKeyReleased(e: NativeKeyEvent) {
        if (e.rawCode == OPTION_RAW_CODE && e.keyCode == VK.SHIFT) {
            val releaseTime = System.currentTimeMillis()
            isAltPressed = false

            if (isHotkeyActive) {
                isHotkeyActive = false
                onPressed(false)
            } else {
                if (releaseTime - lastClickTime < doubleClickThreshold) {
                    onDoubleClick()
                    lastClickTime = 0
                } else {
                    lastClickTime = releaseTime
                }
            }
        }
    }

    override fun nativeKeyTyped(e: NativeKeyEvent) = Unit
}

fun main() {
    val l = LoggerFactory.getLogger("HotkeyListener")
    val hotkeyListener = HotkeyListener(
        onPressed = { pressed ->
            val msg = if (pressed) {
                "onStart"
            } else {
                "onStop"
            }
            l.info(msg)
        },
        onDoubleClick = { l.info("double click") },
    )

    try {
        GlobalScreen.registerNativeHook()
        GlobalScreen.addNativeKeyListener(hotkeyListener)
    } catch (e: NativeHookException) {
        l.error("Failed to register native hook: ${e.message}")
        exitProcess(1)
    }

    Thread.currentThread().join()
}
