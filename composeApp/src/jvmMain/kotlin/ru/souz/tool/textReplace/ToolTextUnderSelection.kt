package ru.souz.tool.textReplace

import ru.souz.tool.FewShotExample
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolRunBashCommand
import ru.souz.tool.ToolSetup

class ToolTextUnderSelection(
    private val bash: ToolRunBashCommand,
    private val toolGetClipboard: ToolGetClipboard,
) : ToolSetup<ToolTextUnderSelection.Input> {
    object Input

    override val name: String = "TextUnderSelection"
    override val description: String = "A tool to provide text user selected"
    override val fewShotExamples = listOf(FewShotExample(request = "Перепиши выбранный текст", params = emptyMap()))
    override val returnParameters: ReturnParameters = ReturnParameters(
        properties = mapOf("result" to ReturnProperty("string", "The selected text in quotes")),
    )

    override fun invoke(input: Input): String {
        return getDataFromSelectionWithKeys()
    }

    private fun getDataFromSelectionWithKeys(): String {
        bash.apple(SCRIPT)
        return toolGetClipboard.invoke(ToolGetClipboard.Input)
    }
}

private const val SCRIPT = """tell application "System Events"
	keystroke "c" using command down
end tell"""