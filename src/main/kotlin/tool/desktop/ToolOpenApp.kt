package com.dumch.tool.desktop

import com.dumch.tool.*

class ToolOpenApp(private val bash: ToolRunBashCommand) : ToolSetup<ToolOpenApp.Input> {
    override val name: String
        get() = "OpenApp"
    override val description: String = "Opens the given app"
    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Launch Calculator",
            params = mapOf("appName" to "Calculator")
        )
    )
    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Operation status")
        )
    )

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

fun main() {
    val tool = ToolOpenApp(ToolRunBashCommand)
    println(tool.invoke(ToolOpenApp.Input("Finder")))
}