package com.dumch.tool.desktop

import com.dumch.tool.InputParamDescription
import com.dumch.tool.ToolRunBashCommand
import com.dumch.tool.ToolSetup

/**
 * Controls media playback, volume, and display brightness via AppleScript.
 *
 * For brightness adjustment actions, this tool invokes the `brightness` command-line
 * utility. Ensure the utility is installed and available on the system path
 * (for example, `brew install brightness` or see https://github.com/nriley/brightness).
 */
class ToolMediaControl(private val bash: ToolRunBashCommand) : ToolSetup<ToolMediaControl.Input> {
    override val name: String = "MediaControl"
    override val description: String = "Controls media playback, volume, and display brightness"

    override fun invoke(input: Input): String {
        val script = when (input.action) {
            "next" -> """tell application \"Music\" to next track"""
            "previous" -> """tell application \"Music\" to previous track"""
            "play" -> """tell application \"Music\" to play"""
            "pause" -> """tell application \"Music\" to pause"""
            "playpause" -> """tell application \"Music\" to playpause"""
            "volume_up" -> """set volume output volume ((output volume of (get volume settings)) + 10)"""
            "volume_down" -> """set volume output volume ((output volume of (get volume settings)) - 10)"""
            "brightness_up" -> """do shell script \"brightness +0.1\""""
            "brightness_down" -> """do shell script \"brightness -0.1\""""
            else -> throw IllegalArgumentException("Unknown action: ${input.action}")
        }
        bash.apple(script)
        return "Done"
    }

    class Input(
        @InputParamDescription("Action to perform: next, previous, play, pause, playpause, brightness_up, brightness_down, volume_up, volume_down")
        val action: String
    )
}
