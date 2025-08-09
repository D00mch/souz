package com.dumch.tool.desktop

import com.dumch.tool.InputParamDescription
import com.dumch.tool.ToolRunBashCommand
import com.dumch.tool.ToolSetup

class ToolCreateNewBrowserTab(private val bash: ToolRunBashCommand) : ToolSetup<ToolCreateNewBrowserTab.Input> {

    override val name: String = "CreateNewBrowserTab"
    override val description: String = "Opens the given url in the new tab if Safari is running"
    override fun invoke(input: Input): String {
        bash.invoke(
            ToolRunBashCommand.Input(
                """
                osascript <<EOF
                    tell application "Safari"
                        activate

                        if (count of windows) > 0 then
                            tell front window
                                set newTab to make new tab with properties {URL:"${'$'}{input.url}"}
                                set current tab to newTab
                            end tell
                        else
                            make new document with properties {URL:"${'$'}{input.url}"}
                        end if
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