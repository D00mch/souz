package ru.gigadesk.tool.textReplace

import ru.gigadesk.keys.SelectedText
import ru.gigadesk.tool.FewShotExample
import ru.gigadesk.tool.InputParamDescription
import ru.gigadesk.tool.ReturnParameters
import ru.gigadesk.tool.ReturnProperty
import ru.gigadesk.tool.ToolSetup

class ToolTextReplace(private val selectedText: SelectedText): ToolSetup<ToolTextReplace.Input> {

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
        selectedText.getOrNull()
            ?: return "Error: no text is currently selected"
        selectedText.replace(input.newText)
        return "ok"
    }
}