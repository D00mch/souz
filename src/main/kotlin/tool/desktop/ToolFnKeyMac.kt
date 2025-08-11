package com.dumch.tool.desktop

import com.dumch.tool.InputParamDescription
import com.dumch.tool.ToolRunBashCommand
import com.dumch.tool.ToolSetup

/**
 * Sends macOS special function keys like brightness, volume, and media controls
 * using AppleScript.
 */
class ToolFnKeyMac(
    private val bash: ToolRunBashCommand = ToolRunBashCommand
) : ToolSetup<ToolFnKeyMac.Input> {

    override val name: String = "FnKey"
    override val description: String = "Presses special function keys (brightness, volume, media) on macOS"

    override fun invoke(input: Input): String {
        val code = when (input.key.lowercase()) {
            "brightness_down" -> 122 // F1
            "brightness_up" -> 120   // F2
            "previous" -> 98         // F7
            "play_pause" -> 100      // F8
            "next" -> 101            // F9
            "mute" -> 109            // F10
            "volume_down" -> 103     // F11
            "volume_up" -> 111       // F12
            else -> error("Unknown key: ${input.key}")
        }
        val script = """
            osascript -e 'tell application "System Events" to key code $code'
        """.trimIndent()
        bash.script(script)
        return "Pressed ${input.key}"
    }

    data class Input(
        @InputParamDescription(
            "Key to press. Options: brightness_down, brightness_up, volume_up, volume_down, mute, play_pause, next, previous"
        )
        val key: String
    )
}

fun main() {
    val tool = ToolFnKeyMac()
    println(tool.invoke(ToolFnKeyMac.Input("volume_up")))
}

