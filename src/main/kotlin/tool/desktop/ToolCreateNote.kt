package com.dumch.tool.desktop

import com.dumch.tool.InputParamDescription
import com.dumch.tool.ToolRunBashCommand
import com.dumch.tool.ToolSetup

class ToolCreateNote(private val bash: ToolRunBashCommand) : ToolSetup<ToolCreateNote.Input> {

    override val name: String = "CreateNote"
    override val description: String = "Opens Notes and create new note with text"
    override fun invoke(input: Input): String {
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

    class Input(
        @InputParamDescription("Text of note")
        val noteText: String
    )
}
