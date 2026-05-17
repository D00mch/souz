package ru.souz.tool.notes

import ru.souz.llms.ToolInvocationMeta

import ru.souz.tool.FewShotExample
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolRunBashCommand
import ru.souz.tool.ToolSetup

class ToolListNotes(private val bash: ToolRunBashCommand) : ToolSetup<ToolListNotes.Input> {
    // No parameters required
    object Input

    override val name: String = "ListNotes"
    override val description: String = "Lists all existing note names"

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Покажи список заметок",
            params = emptyMap()
        )
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Comma separated list of note names")
        )
    )

    override fun invoke(input: Input, meta: ToolInvocationMeta): String {
        val result = bash.apple(
            """
                tell application "Notes"
                    set noteNames to {}
                    repeat with n in every note
                        copy name of n to end of noteNames
                    end repeat

                    if (count of noteNames) = 0 then
                        return ""
                    else
                        return noteNames as string
                    end if
                end tell
            """.trimIndent()
        )

        return result.ifBlank { "No notes found" }
    }

    override suspend fun suspendInvoke(input: Input, meta: ToolInvocationMeta): String = invoke(input, meta)
}
