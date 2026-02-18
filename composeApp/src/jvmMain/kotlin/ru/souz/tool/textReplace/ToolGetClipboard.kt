package ru.souz.tool.textReplace

import ru.souz.keys.MrRobot
import ru.souz.tool.FewShotExample
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolSetup

class ToolGetClipboard : ToolSetup<ToolGetClipboard.Input> {
    object Input

    override val name: String = "GetClipboard"
    override val description: String = "Returns the data in the clipboard"
    override val fewShotExamples = listOf(FewShotExample(request = "Что в буффере обмена?", params = emptyMap()))
    override val returnParameters: ReturnParameters = ReturnParameters(
        properties = mapOf("result" to ReturnProperty("string", "The text from clipboard in quot")),
    )

    override fun invoke(input: Input): String {
        return getDataFromSelectionWithKeys()
    }

    private fun getDataFromSelectionWithKeys(): String {
        return MrRobot.clipboardGet() ?: "Error: \"Nothing in selection\""
    }
}
