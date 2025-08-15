package com.dumch.tool.config

import com.dumch.tool.*


class ToolSoundConfig(private val config: ConfigStore) : ToolSetup<ToolSoundConfig.Input> {
    data class Input(
        @InputParamDescription("Speed diff to apply to the current speed")
        val diff: Int,
        @InputParamDescription("Desired speed for speech synthesis, but default it's the user current speed")
        val speed: Int? = ConfigStore.get(SPEED_KEY, DEFAULT_SPEED),
    )

    override val name: String = "SoundConfig"
    override val description: String = "Updates sound configuration such as speed"

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Установи скорость речи на 180 символов в секунду",
            params = mapOf("diff" to 0, "speed" to 180)
        ),
        FewShotExample(
            request = "Можешь ускорить воспроизведение звука",
            params = mapOf("diff" to 40)
        ),
        FewShotExample(
            request = "Можешь совсем немногожко замедлить речь",
            params = mapOf("diff" to -20)
        ),
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Confirmation message or error")
        )
    )

    override fun invoke(input: Input): String {
        val currentSpeed = input.speed ?: ConfigStore.get(SPEED_KEY, DEFAULT_SPEED)
        val newSpeed = currentSpeed + input.diff
        config.put(SPEED_KEY, newSpeed)
        return "Sound speed updated to ${input.diff}"
    }

    companion object {
        const val SPEED_KEY = "sound_speed"
        const val DEFAULT_SPEED = 230
    }
}
