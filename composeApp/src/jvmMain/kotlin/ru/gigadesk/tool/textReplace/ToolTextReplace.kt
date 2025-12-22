package ru.gigadesk.tool.textReplace

import ru.gigadesk.keys.HotKey
import ru.gigadesk.keys.Keys
import ru.gigadesk.keys.MrRobot
import ru.gigadesk.tool.FewShotExample
import ru.gigadesk.tool.InputParamDescription
import ru.gigadesk.tool.ReturnParameters
import ru.gigadesk.tool.ReturnProperty
import ru.gigadesk.tool.ToolRunBashCommand
import ru.gigadesk.tool.ToolSetup

class ToolTextReplace(
    private val bash: ToolRunBashCommand
) : ToolSetup<ToolTextReplace.Input> {

    data class Input(
        @InputParamDescription("The newText that will replace the text under selection")
        val newText: String
    )

    override val name: String = "TextReplace"
    override val description: String = "Replace the text that is in selection with the newText"

    override val fewShotExamples: List<FewShotExample> = listOf(
        FewShotExample(
            request = "Перепиши выделенный текст",
            params = mapOf("newText" to "Новый текст тут")
        )
    )

    override val returnParameters: ReturnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Ok on success, or Error message of failure")
        )
    )

    override fun invoke(input: Input): String {
        MrRobot.clipboardPut(input.newText)
        bash.apple(SCRIPT)
        return "ok"
    }
}

private const val SCRIPT = """
tell application "System Events"
	keystroke "v" using command down
end tell
        """