package ru.abledo.tool.notes

import ru.abledo.tool.BadInputException
import ru.abledo.tool.FewShotExample
import ru.abledo.tool.InputParamDescription
import ru.abledo.tool.ReturnParameters
import ru.abledo.tool.ReturnProperty
import ru.abledo.tool.ToolRunBashCommand
import ru.abledo.tool.ToolSetup

class ToolSearchNotes(private val bash: ToolRunBashCommand) : ToolSetup<ToolSearchNotes.Input> {
    data class Input(
        @InputParamDescription("Text to search in note name or body")
        val query: String,
    )

    override val name: String = "SearchNotes"
    override val description: String = "Searches notes by text in the name or body and returns matching note names"

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Найди заметку про отпуск",
            params = mapOf("query" to "отпуск")
        )
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Comma separated list of matching note names")
        )
    )

    override fun invoke(input: Input): String {
        if (input.query.isBlank()) throw BadInputException("Search query cannot be empty")

        val result = bash.apple(
            """
                tell application "Notes"
                    set searchText to "${input.query}"
                    set matchedNotes to (every note whose name contains searchText or body contains searchText)

                    if (count of matchedNotes) = 0 then
                        return ""
                    else
                        set noteNames to {}
                        repeat with n in matchedNotes
                            copy name of n to end of noteNames
                        end repeat
                        return noteNames as string
                    end if
                end tell
            """.trimIndent()
        )

        return result.ifBlank { "No matches" }
    }
}
