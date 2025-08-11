package com.dumch.tool.desktop

import com.dumch.tool.InputParamDescription
import com.dumch.tool.ToolRunBashCommand
import com.dumch.tool.ToolSetup

class ToolMediaControl(private val bash: ToolRunBashCommand) : ToolSetup<ToolMediaControl.Input> {
    data class Input(
        @InputParamDescription("Action to perform: brightness_up, brightness_down, volume_up, volume_down")
        val action: String
    )

    override val name: String = "VolumeAndBrightness"
    override val description: String = "Controls volume and display brightness"

    override fun invoke(input: Input): String {
        val script = when (input.action) {
            // remember to change description
            "next" -> ""
            "previous" -> ""
            "playpause" -> ""
            "volume_up" -> "set volume output volume ((output volume of (get volume settings)) + 10)"   // works
            "volume_down" -> "set volume output volume ((output volume of (get volume settings)) - 10)" // works
            "brightness_down" -> "tell application \"System Events\" to key code 145"
            "brightness_up" -> "tell application \"System Events\" to key code 144"
            else -> throw IllegalArgumentException("Unknown action: ${input.action}")
        }
        bash.apple(script)
        return "Done"
    }
}

fun main() {
    val r = ToolMediaControl(ToolRunBashCommand).invoke(ToolMediaControl.Input("playpause"))
    println(r)
}
