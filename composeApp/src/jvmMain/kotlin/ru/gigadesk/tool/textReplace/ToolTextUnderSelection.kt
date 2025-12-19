package ru.gigadesk.tool.textReplace

import ru.gigadesk.keys.HotKey
import ru.gigadesk.keys.Keys
import ru.gigadesk.keys.SelectedText
import ru.gigadesk.tool.FewShotExample
import ru.gigadesk.tool.ReturnParameters
import ru.gigadesk.tool.ToolSetup

class ToolTextUnderSelection(
    private val selectedText: SelectedText,
    private val keys: Keys,
) : ToolSetup<ToolTextUnderSelection.Input> {
    object Input

    override val name: String = "TextUnderSelection"
    override val description: String = "A tool to provide text user selected"
    override val fewShotExamples = listOf(FewShotExample(request = "Перепиши выбранный текст", params = emptyMap()))
    override val returnParameters: ReturnParameters = ReturnParameters(properties = emptyMap())

    override fun invoke(input: Input): String {
        val textIsSelectedByOS = selectedText.getOrNull()
        return textIsSelectedByOS ?: TODO()
    }

    private fun getDataFromSelectionWithKeys(): String {
        keys.press(HotKey.paste)  // move data to clipboard
        TODO()
    }
}