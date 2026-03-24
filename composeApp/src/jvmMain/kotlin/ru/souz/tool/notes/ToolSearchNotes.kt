package ru.souz.tool.notes

import ru.souz.tool.BadInputException
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolActionDescriptor
import ru.souz.tool.ToolActionKind
import ru.souz.tool.ToolActionValueFormatter
import ru.souz.tool.ToolRunBashCommand
import ru.souz.tool.ToolSetup

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

    override fun describeAction(input: Input): ToolActionDescriptor? = ToolActionDescriptor(
        kind = ToolActionKind.SEARCH_NOTES,
        primary = ToolActionValueFormatter.compactText(input.query),
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
