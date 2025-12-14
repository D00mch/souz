package ru.abledo.tool.notes

import ru.abledo.tool.BadInputException
import ru.abledo.tool.FewShotExample
import ru.abledo.tool.InputParamDescription
import ru.abledo.tool.ReturnParameters
import ru.abledo.tool.ReturnProperty
import ru.abledo.tool.ToolRunBashCommand
import ru.abledo.tool.ToolSetup

class ToolDeleteNote(private val bash: ToolRunBashCommand) : ToolSetup<ToolDeleteNote.Input> {
    data class Input(
        @InputParamDescription("Note name")
        val noteName: String,
    )

    override val name: String = "DeleteNote"
    override val description: String = "Deletes a note by its name"

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Удалить заметку демо",
            params = mapOf("noteName" to "Демо")
        )
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Operation status")
        )
    )

    override fun invoke(input: Input): String {
        if (input.noteName.isBlank()) throw BadInputException("Note name cannot be empty")

        bash.apple(
            """
                tell application "Notes"
                    set noteName to "${input.noteName}"
                    set notesToDelete to (every note whose name is noteName)

                    if (count of notesToDelete) > 0 then
                        delete (item 1 of notesToDelete)
                    else
                        error "Note with name ${input.noteName} was not found"
                    end if
                end tell
            """.trimIndent()
        )

        return "Done"
    }
}
