package com.dumch.tool.desktop

import com.dumch.giga.objectMapper
import com.dumch.giga.toGiga
import com.dumch.tool.*
import com.dumch.libs.MediaKeysNative

class ToolMediaControl(private val bash: ToolRunBashCommand) : ToolSetup<ToolMediaControl.Input> {
    enum class Action {
        next, previous, playpause, volume_up, volume_down, brightness_down, brightness_up
    }

    data class Input(
        @InputParamDescription("Action to perform")
        val action: Action
    )

    override val name: String = "VolumeAndBrightness"
    override val description: String = "Controls volume and display brightness"
    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Сделай громкость повыше",
            params = mapOf("action" to Action.volume_up)
        )
    )
    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Operation status")
        )
    )

    val mediaKeys = MediaKeysNative()

    override fun invoke(input: Input): String {
        val script = when (input.action) {
            Action.next -> mediaKeys.nextTrack()
            Action.previous ->  mediaKeys.previousTrack()
            Action.playpause ->  mediaKeys.playPause()
            Action.volume_up -> "set volume output volume ((output volume of (get volume settings)) + 10)"
            Action.volume_down -> "set volume output volume ((output volume of (get volume settings)) - 10)"
            Action.brightness_down -> "tell application \"System Events\" to key code 145"
            Action.brightness_up -> "tell application \"System Events\" to key code 144"
        }
        bash.apple(script)
        return "Done"
    }
}

fun main() {
    val t = ToolMediaControl(ToolRunBashCommand)
    t.invoke(ToolMediaControl.Input(ToolMediaControl.Action.playpause))
    println(objectMapper.writeValueAsString(t.toGiga().fn))
}
