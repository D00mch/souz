package ru.abledo.tool.desktop

import ru.abledo.tool.*

class ToolCreateNote(private val bash: ToolRunBashCommand) : ToolSetup<ToolCreateNote.Input> {
    data class Input(
        @InputParamDescription("Text of note")
        val noteText: String
    )
    override val name: String = "CreateNote"
    override val description: String = "Opens Notes and create new note with text"
    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Создай заметку, чтобы купить молоко в субботу",
            params = mapOf("noteText" to "Купить молоко в субботу")
        )
    )
    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Operation status")
        )
    )
    override fun invoke(input: Input): String {
        if (input.noteText.isBlank()) throw BadInputException("Note text cannot be empty")
        bash.invoke(
            ToolRunBashCommand.Input(
                """
                osascript <<EOF
                    tell application "Notes"
                     activate
                     make new note at account 1 with properties {body:"${input.noteText}"}
                    end tell
                EOF
            """.trimIndent()
            )
        )
        return "Done"
    }
}
