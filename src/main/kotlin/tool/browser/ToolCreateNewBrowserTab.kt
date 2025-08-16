package com.dumch.tool.browser

import com.dumch.tool.*

class ToolCreateNewBrowserTab(private val bash: ToolRunBashCommand) : ToolSetup<ToolCreateNewBrowserTab.Input> {
    data class Input(
        @InputParamDescription("The url to open, e.g., 'https://www.sberbank.ru'")
        val url: String
    )
    override val name: String = "CreateNewBrowserTab"
    override val description: String = "Opens the given url in the new tab if Safari is running " +
            "or opens the url in the default browser"
    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Открой google в новой вкладке",
            params = mapOf("url" to "https://www.google.com")
        )
    )
    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Operation status, e.g., 'Done'")
        )
    )
    override fun invoke(input: Input): String {
        if (input.url.isBlank()) throw BadInputException("The url is empty. Can't open it")
        bash.invoke(
            ToolRunBashCommand.Input(
                """
                osascript <<EOF
                    tell application "Safari"
                        activate

                        if (count of windows) > 0 then
                            tell front window
                                set newTab to make new tab with properties {URL:"${input.url}"}
                                set current tab to newTab
                            end tell
                        else
                            make new document with properties {URL:"${input.url}"}
                        end if
                    end tell
                EOF
            """.trimIndent()
            )
        )
        return "Done"
    }
}