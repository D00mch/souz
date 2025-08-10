package com.dumch.tool.desktop

import com.dumch.tool.InputParamDescription
import com.dumch.tool.ToolRunBashCommand
import com.dumch.tool.ToolSetup

class ToolOpenFile(private val bash: ToolRunBashCommand) : ToolSetup<ToolOpenFile.Input> {
    override val name: String
        get() = "OpenFile"
    override val description: String = "Opens the file at the given path in the default app"

    override fun invoke(input: Input): String {
        bash.invoke(ToolRunBashCommand.Input("""open "${input.filePath}""""))
        return "Done"
    }

    data class Input(
        @InputParamDescription("The full path to the file to open, e.g., '\$HOME/Pictures/портрет.jpeg'")
        val filePath: String,
    )
}

fun main() {
    val v = ToolOpenFile(ToolRunBashCommand)(ToolOpenFile.Input("\$HOME/Pictures/"))
    println(v)
}