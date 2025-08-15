package com.dumch.tool.config

import com.dumch.tool.*

class ToolSoundConfigDiff(private val config: ConfigStore) : ToolSetup<ToolSoundConfigDiff.Input> {
    data class Input(
        @InputParamDescription("Speed diff to apply to the current speed")
        val diff: Int,
    )

    override val name: String = "SoundConfigDiff"
    override val description: String = "Updates sound speed by applying diff to current speed"

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Сделай скорость речи медленнее",
            params = mapOf("diff" to -40)
        ),
        FewShotExample(
            request = "Сделай скорость речи намного быстрее",
            params = mapOf("diff" to 80)
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
        val currentSpeed = ConfigStore.get(ToolSoundConfig.SPEED_KEY, ToolSoundConfig.DEFAULT_SPEED)
        val newSpeed = currentSpeed + input.diff
        config.put(ToolSoundConfig.SPEED_KEY, newSpeed)
        return "Sound speed updated to $newSpeed"
    }
}
