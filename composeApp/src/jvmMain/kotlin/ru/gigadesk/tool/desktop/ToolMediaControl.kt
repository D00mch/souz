package ru.gigadesk.tool.desktop

import ru.gigadesk.giga.gigaJsonMapper
import ru.gigadesk.giga.toGiga
import ru.gigadesk.tool.*
import ru.gigadesk.libs.MediaKeysNative

class ToolMediaControl(private val bash: ToolRunBashCommand) : ToolSetup<ToolMediaControl.Input> {
    enum class Action {
        next, previous, playpause, volume_up, volume_down, brightness_down, brightness_up
    }

    data class Input(
        @InputParamDescription("Action to perform")
        val action: Action
    )

    override val name: String = "MediaControl"
    override val description: String = "Controls volume, brightness, next track, previous track, play/pause"
    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Сделай громкость повыше",
            params = mapOf("action" to Action.volume_up)
        ),
        FewShotExample(
            request = "Нажми на плей",
            params = mapOf("action" to Action.playpause)
        ),
        FewShotExample(
            request = "Повысь яркость",
            params = mapOf("action" to Action.brightness_up)
        ),
        FewShotExample(
            request = "Запусти музыку",
            params = mapOf("action" to Action.playpause)
        ),
        FewShotExample(
            request = "Поставь на паузу",
            params = mapOf("action" to Action.playpause)
        ),
        FewShotExample(
            request = "Перейди на следующую песню",
            params = mapOf("action" to Action.next)
        )
    )
    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Operation status")
        )
    )

    val mediaKeys = MediaKeysNative()

    override fun invoke(input: Input): String {
        when (input.action) {
            Action.next -> mediaKeys.nextTrack()
            Action.previous -> mediaKeys.previousTrack()
            Action.playpause -> mediaKeys.playPause()
            Action.volume_up -> bash.apple("set volume output volume ((output volume of (get volume settings)) + 10)")
            Action.volume_down -> bash.apple("set volume output volume ((output volume of (get volume settings)) - 10)")
            Action.brightness_down -> bash.apple("tell application \"System Events\" to key code 145")
            Action.brightness_up -> bash.apple("tell application \"System Events\" to key code 144")
        }
        return "Done"
    }
}

fun main() {
    val t = ToolMediaControl(ToolRunBashCommand)
    t.invoke(ToolMediaControl.Input(ToolMediaControl.Action.playpause))
    println(gigaJsonMapper.writeValueAsString(t.toGiga().fn))
}
