package com.dumch.tool.desktop

import com.dumch.tool.InputParamDescription
import com.dumch.tool.ToolRunBashCommand
import com.dumch.tool.ToolSetup
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.text.replace

class ToolOpenFile(private val bash: ToolRunBashCommand) : ToolSetup<ToolOpenFile.Input> {
    private val l = LoggerFactory.getLogger(ToolOpenFile::class.java)

    override val name: String = "OpenFile"
    override val description: String = "Opens the file at the given path in the default app. Use it to open photos as well"

    override fun invoke(input: Input): String {
        val fixedPath = input.filePath.replace("\$HOME", System.getenv("HOME"))
        l.info("Opening file '$fixedPath'")
        try {
            val isFolder = File(fixedPath).isDirectory
            if (isFolder) {
                return ToolOpenFolder(bash).invoke(ToolOpenFolder.Input(input.filePath.split("/").last()))
            }
            bash.sh("""open "${input.filePath}"""")
        } catch (e: Exception) {
            l.error("Error opening file: ${e.message}")
            ToolOpenFolder(bash).invoke(ToolOpenFolder.Input(fixedPath))
        }
        return "Done"
    }

    data class Input(
        @InputParamDescription("The full path to the file to open, e.g., '\$HOME/Pictures/портрет.jpeg'")
        val filePath: String,
    )
}

fun main() {
//    println(
//        "\$HOME/семья".replace("\$HOME", System.getenv("HOME"))
//    )

    val v = ToolOpenFile(ToolRunBashCommand)(ToolOpenFile.Input("\$HOME/семья"))
    println(v)
}