package ru.souz.tool.config

import ru.souz.db.ConfigStore
import ru.souz.llms.ToolInvocationMeta
import ru.souz.tool.*

class ToolSoundConfig(private val config: ConfigStore) : ToolSetup<ToolSoundConfig.Input> {
    data class Input(
        @InputParamDescription("Desired speed for speech synthesis")
        val speed: Int,
    )

    override val name: String = "SoundConfig"
    override val description: String = "Sets sound configuration such as speed"

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Установи скорость речи на 180 символов в секунду",
            params = mapOf("speed" to 180)
        ),
        FewShotExample(
            request = "Можешь поставить скорость речи на среднюю скорость",
            params = mapOf("speed" to 140)
        ),
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Confirmation message or error")
        )
    )

    override fun invoke(input: Input, meta: ToolInvocationMeta): String {
        val newSpeed = input.speed
        config.put(SPEED_KEY, newSpeed)
        return "Sound speed updated to ${input.speed}"
    }

    companion object {
        const val SPEED_KEY = "sound_speed"
        const val DEFAULT_SPEED = 230
    }
}
