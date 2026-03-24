package ru.souz.tool.notes

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

    override fun describeAction(input: Input): ToolActionDescriptor? = ToolActionDescriptor(
        kind = ToolActionKind.OPEN_NOTE,
        primary = ToolActionValueFormatter.compactText(input.noteName),
    )

    override fun invoke(input: Input): String {
        if (input.noteName.isBlank()) throw BadInputException("Note name cannot be empty")
        bash.apple(
            """
                        tell application "Notes"
                            activate
                            set noteName to "${input.noteName}" 
                            set foundNotes to (notes whose name is noteName)
                            
                            if (count of foundNotes) > 0 then
                                set firstNote to item 1 of foundNotes
                                show firstNote
                            else
                                display dialog "Заметка с названием ${input.noteName} не найдена." buttons {"OK"} default button 1
                            end if
                        end tell
                    """).trimIndent()

        return "Done"
    }
}

fun main() {
    val tool = ToolOpenNote(ToolRunBashCommand)
    println(tool.invoke(ToolOpenNote.Input("Демо")))
}
