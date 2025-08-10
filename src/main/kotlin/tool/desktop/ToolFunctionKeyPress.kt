package com.dumch.tool.desktop

import com.dumch.keys.*
import com.dumch.tool.InputParamDescription
import com.dumch.tool.ToolSetup

class ToolFnKeyMac : ToolSetup<ToolFnKeyMac.Input> {
    private val cg = CoreGraphics.INSTANCE
    private val cf = CoreFoundation.INSTANCE

    override val name = "FnKey"
    override val description = "Presses special function keys like brightness, volume, or media controls (macOS)."

    class Input(
        @InputParamDescription("""Options:
            "brightness_down" -> F1
            "brightness_up"   -> F2
            "prev_track"      -> F7
            "play_pause"      -> F8
            "next_track"      -> F9
            "volume_mute"     -> F10
            "volume_down"     -> F11
            "volume_up"       -> F12""")
        val key: String
    )

    override fun invoke(input: Input): String {
        require(System.getProperty("os.name").contains("Mac", ignoreCase = true)) {
            "This implementation supports macOS only."
        }

        fun post(key: Int, down: Boolean) {
            val evt = cg.CGEventCreateKeyboardEvent(null, key, down)
            cg.CGEventPost(CG.kCGHIDEventTap, evt)
            cf.CFRelease(evt)
        }
        fun press(k: Int) { post(k, true); post(k, false) }

        when (input.key.lowercase()) {
            "brightness_down" -> press(VK.F1)
            "brightness_up"   -> press(VK.F2)
            "prev_track"      -> press(VK.F7)
            "play_pause"      -> press(VK.F8)
            "next_track"      -> press(VK.F9)
            "volume_mute"     -> press(VK.F10)
            "volume_down"     -> press(VK.F11)
            "volume_up"       -> press(VK.F12)
            else -> error("Unknown fn key: ${input.key}")
        }

        return "Pressed ${input.key}"
    }
}

suspend fun main() {
    val tool = ToolFnKeyMac()
    println(tool.invoke(ToolFnKeyMac.Input("volume_up")))
}
