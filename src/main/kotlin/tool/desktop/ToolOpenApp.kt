package com.dumch.tool.desktop

import com.dumch.tool.InputParamDescription
import com.dumch.tool.ToolRunBashCommand
import com.dumch.tool.ToolSetup

class ToolOpenApp(private val bash: ToolRunBashCommand) : ToolSetup<ToolOpenApp.Input> {
    override val name: String
        get() = "OpenApp"
    override val description: String = "Opens the given app"

    override fun invoke(input: Input): String {
        bash.invoke(
            ToolRunBashCommand.Input("""open -a "${input.appName}"""")
        )
        return "Done"
    }

    class Input(
        @InputParamDescription("The name of the app to open, e.g., 'Safari', 'Calendar', 'Telegram'")
        val appName: String
    )
}