package ru.souz.tool.notes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import ru.souz.llms.ToolInvocationMeta

import ru.souz.tool.*

class ToolOpenNote(private val bash: ToolRunBashCommand) : ToolSetup<ToolOpenNote.Input> {
    data class Input(
        @InputParamDescription("Note name")
        val noteName: String
    )
    override val name: String = "OpenNote"
    override val description: String = "Opens Notes by its name"
    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Открой заметку демо",
            params = mapOf("noteName" to "Демо")
        )
    )
    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Operation status")
        )
    )
    override fun invoke(input: Input, meta: ToolInvocationMeta): String {
        if (input.noteName.isBlank()) throw BadInputException("Note name cannot be empty")
        return bash.apple(
            """
                on run argv
                    set noteName to item 1 of argv
                    tell application "Notes"
                        activate
                        set foundNotes to (notes whose name is noteName)
                        if (count of foundNotes) > 0 then
                            set firstNote to item 1 of foundNotes
                            show firstNote
                            return "Done"
                        else
                            return "Note not found: " & noteName
                        end if
                    end tell
                end run
            """.trimIndent(),
            args = listOf(input.noteName),
            timeoutMillis = 30_000,
        )
    }

    override suspend fun suspendInvoke(input: Input, meta: ToolInvocationMeta): String =
        runInterruptible(Dispatchers.IO) { invoke(input, meta) }
}

fun main() {
    val tool = ToolOpenNote(ToolRunBashCommand)
    println(tool.invoke(ToolOpenNote.Input("Демо"), ToolInvocationMeta.localDefault()))
}
