package com.dumch.tool.desktop

import com.dumch.tool.InputParamDescription
import com.dumch.tool.ToolRunBashCommand
import com.dumch.tool.ToolSetup

class ToolMediaControlMac(private val bash: ToolRunBashCommand) : ToolSetup<ToolMediaControlMac.Input> {
    override val name: String = "MediaControl"
    override val description: String = "Controls media playback, brightness and volume (macOS)"

    data class Input(
        @InputParamDescription("Command to execute: play_pause, next_track, previous_track, volume_up, volume_down, brightness_up, brightness_down")
        val command: String,
    )

    override fun invoke(input: Input): String {
        val script = when (input.command.lowercase()) {
            "play_pause", "play", "pause" -> "tell application \"System Events\" to key code 100" // F8
            "next_track", "next" -> "tell application \"System Events\" to key code 101" // F9
            "previous_track", "prev", "previous" -> "tell application \"System Events\" to key code 98" // F7
            "volume_up" -> """
                set currentVol to output volume of (get volume settings)
                set newVol to currentVol + 10
                if newVol > 100 then set newVol to 100
                set volume output volume newVol
            """.trimIndent()
            "volume_down" -> """
                set currentVol to output volume of (get volume settings)
                set newVol to currentVol - 10
                if newVol < 0 then set newVol to 0
                set volume output volume newVol
            """.trimIndent()
            "brightness_up" -> "tell application \"System Events\" to key code 120" // F2
            "brightness_down" -> "tell application \"System Events\" to key code 122" // F1
            else -> error("Unknown command: ${input.command}")
        }
        bash.apple(script)
        return "Done"
    }
}

fun main() {
    val tool = ToolMediaControlMac(ToolRunBashCommand)
    println(tool.invoke(ToolMediaControlMac.Input("play_pause")))
}
