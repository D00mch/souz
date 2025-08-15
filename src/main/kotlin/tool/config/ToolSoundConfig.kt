package com.dumch.tool.config

import com.dumch.tool.*

private const val SPEED_KEY = "sound_speed"

object ToolSoundConfig : ToolSetup<ToolSoundConfig.Input> {
    override val name: String = "SoundConfig"
    override val description: String = "Updates sound configuration such as speed"

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Set sound speed to 180",
            params = mapOf("speed" to 180)
        )
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Confirmation message")
        )
    )

    override fun invoke(input: Input): String {
        ConfigStore.put(SPEED_KEY, input.speed)
        return "Sound speed updated to ${input.speed}"
    }

    data class Input(
        @InputParamDescription("Desired speed for speech synthesis")
        val speed: Int
    )
}
