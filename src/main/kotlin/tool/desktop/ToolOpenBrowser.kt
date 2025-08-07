package com.dumch.tool.desktop

import com.dumch.tool.InputParamDescription
import com.dumch.tool.ToolRunBashCommand
import com.dumch.tool.ToolSetup

class ToolOpenBrowser(private val bash: ToolRunBashCommand) : ToolSetup<ToolOpenBrowser.Input> {

    override val name: String = "OpenBrowser"
    override val description: String = "Opens the given url in the default browser"
    override fun invoke(input: Input): String {
        bash.invoke(
            ToolRunBashCommand.Input(
                """
                osascript <<EOF
                    tell application "Safari"
                        activate
                        make new document with properties {URL:"${input.url}"}
                    end tell
                EOF
            """.trimIndent()
            )
        )
        return "Done"
    }

    class Input(
        @InputParamDescription("The url to open, e.g., 'https://www.sberbank.ru'")
        val url: String
    )
}