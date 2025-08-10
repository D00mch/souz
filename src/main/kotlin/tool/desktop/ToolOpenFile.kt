package com.dumch.tool.desktop

import com.dumch.tool.InputParamDescription
import com.dumch.tool.ToolRunBashCommand
import com.dumch.tool.ToolSetup
import org.slf4j.LoggerFactory

class ToolOpenFile(private val bash: ToolRunBashCommand) : ToolSetup<ToolOpenFile.Input> {
    private val l = LoggerFactory.getLogger(ToolOpenFile::class.java)

    override val name: String = "OpenFile"
    override val description: String = "Opens the file at the given path in the default app. Use it to open photos as well"

    override fun invoke(input: Input): String {
        try {
            bash.sh("""open "${input.filePath}"""")
        } catch (e: Exception) {
            l.error("Error opening file: ${e.message}")
            ToolOpenFolder(bash).invoke(ToolOpenFolder.Input(input.filePath))
        }
        return "Done"
    }

    data class Input(
        @InputParamDescription("The full path to the file to open, e.g., '\$HOME/Pictures/портрет.jpeg'")
        val filePath: String,
    )
}

fun main() {
    val v = ToolOpenFile(ToolRunBashCommand)(ToolOpenFile.Input("семья"))
    println(v)
}