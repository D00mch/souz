package ru.souz.tool.notes

import ru.souz.tool.BadInputException
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolPermissionBroker
import ru.souz.tool.ToolPermissionResult
import ru.souz.tool.ToolRunBashCommand
import ru.souz.tool.ToolSetup
import souz.composeapp.generated.resources.Res
import souz.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

class ToolDeleteNote(
    private val bash: ToolRunBashCommand,
    private val permissionBroker: ToolPermissionBroker? = null,
) : ToolSetup<ToolDeleteNote.Input> {
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

    override suspend fun suspendInvoke(input: Input): String {
        val result = permissionBroker?.requestPermission(
            getString(Res.string.permission_delete_note),
            linkedMapOf("noteName" to input.noteName)
        )
        if (result is ToolPermissionResult.No) return result.msg
        return invoke(input)
    }

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
