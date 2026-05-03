package ru.souz.tool.notes

import ru.souz.llms.ToolInvocationMeta

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

class ToolCreateNote(
    private val bash: ToolRunBashCommand,
    private val permissionBroker: ToolPermissionBroker? = null,
) : ToolSetup<ToolCreateNote.Input> {
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

    override suspend fun suspendInvoke(input: Input, meta: ToolInvocationMeta): String {
        val result = permissionBroker?.requestPermission(
            getString(Res.string.permission_create_note),
            linkedMapOf("noteText" to input.noteText)
        )
        if (result is ToolPermissionResult.No) return result.msg
        return invoke(input, meta)
    }

    override fun invoke(input: Input, meta: ToolInvocationMeta): String {
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
            ),
            meta,
        )
        return "Done"
    }
}
