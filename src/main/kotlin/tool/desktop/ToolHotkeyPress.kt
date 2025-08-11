package com.dumch.tool.desktop

import com.dumch.keys.*
import com.dumch.tool.InputParamDescription
import com.dumch.tool.ToolSetup
import java.lang.Thread.sleep

class ToolHotkeyMac : ToolSetup<ToolHotkeyMac.Input> {
    private val cg = CoreGraphics.INSTANCE
    private val cf = CoreFoundation.INSTANCE

    override val name = "Hotkey"
    override val description = "Press keys provided in a list like [\"space\", \"close_tab\"]"

    class Input(
        @InputParamDescription(
            """Return a list of strings to press a hotkey. Example ["full_screen_toggle"]. Keys options:
            "arrow_left"           -> left arrow
            "arrow_right"          -> right arrow
            "arrow_up"             -> arrow up
            "arrow_down"           -> arrow down
            "page_up"              -> page up
            "page_down"            -> page down
            "home"                 -> home
            "end"                  -> end
            "backspace"            -> label "Delete" on Mac keyboards
            "enter"                -> enter
            "escape"               -> escape
            "space"                -> space
            "go_to_left_screen"    -> ctrl+left
            "go_to_right_screen"   -> ctrl+right
            "full_screen_toggle"   -> ctrl+cmd+f
            "close_app"            -> cmd+q
            "close_tab"            -> cmd+w
            "open_just_closed_tab" -> cmd+shift+t
            "cancel_last_action"   -> cmd+z"""
        )
        val keys: List<String>
    )

    override fun invoke(input: Input): String {
        require(System.getProperty("os.name").contains("Mac", ignoreCase = true)) {
            "This implementation supports macOS only."
        }

        input.keys.forEach { key ->
            extracted(key)
        }

        return "Pressed ${input.keys.joinToString(", ")}"
    }

    private fun post(key: Int, down: Boolean) {
        val evt = cg.CGEventCreateKeyboardEvent(null, key, down)
        cg.CGEventPost(CG.kCGHIDEventTap, evt)
        cf.CFRelease(evt)
    }
    private fun keyDown(k: Int) = post(k, true)
    private fun keyUp(k: Int) = post(k, false)
    private fun press(k: Int) { keyDown(k); keyUp(k) }
    private fun combo(k: Int, vararg mods: Int) {
        mods.forEach {
            sleep(20)
            keyDown(it)
        }
        sleep(20)
        press(k)
        mods.forEach { keyUp(it) }
    }

    private fun extracted(key: String) {
        when (key.lowercase()) {
            "arrow_left" -> press(VK.LEFT)
            "arrow_right" -> press(VK.RIGHT)
            "arrow_up" -> press(VK.UP)
            "arrow_down" -> press(VK.DOWN)
            "page_up" -> press(VK.PAGE_UP)
            "page_down" -> press(VK.PAGE_DOWN)
            "home" -> press(VK.HOME)
            "end" -> press(VK.END)
            "delete" -> press(VK.FORWARD_DELETE)    // forward delete
            "backspace" -> press(VK.BACKSPACE)      // label "Delete" on Mac keyboards
            "enter" -> press(VK.RETURN)
            "escape" -> press(VK.ESC)
            "space" -> press(VK.SPACE)
            "go_to_left_screen" -> combo(VK.LEFT, VK.CTRL)           // ctrl+left
            "go_to_right_screen" -> combo(VK.RIGHT, VK.CTRL)         // ctrl+right
            "full_screen_toggle" -> combo(VK.F, VK.CMD, VK.CTRL)     // ctrl+cmd+f
            "close_app" -> combo(VK.Q, VK.CMD)                       // cmd+q
            "close_tab" -> combo(VK.W, VK.CMD)                       // cmd+w
            "open_just_closed_tab" -> combo(VK.T, VK.CMD, VK.SHIFT)  // cmd+shift+t
            "cancel_last_action" -> combo(VK.Z, VK.CMD)              // cmd+z
            else -> error("Unknown hotkey: $key")
        }
    }
}

fun main() {
    val tool = ToolHotkeyMac()
    println(tool.invoke(ToolHotkeyMac.Input(listOf("close_tab", "close_tab"))))
}
