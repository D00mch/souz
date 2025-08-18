package com.dumch.tool.desktop

import com.dumch.tool.*
import org.slf4j.LoggerFactory
import java.io.File

class ToolOpen(private val bash: ToolRunBashCommand) : ToolSetup<ToolOpen.Input> {
    private val l = LoggerFactory.getLogger(ToolOpen::class.java)

    data class Input(
        @InputParamDescription("Name, path or bundle id of app/file/folder to open")
        val target: String
    )

    override val name: String = "Open"
    override val description: String = "Opens apps, files or folders. If you have two candidates to open, choose" +
            "the one with the shortest path, but tell the user that there are other options."

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Открой Safari",
            params = mapOf("target" to "com.apple.Safari")
        ),
        FewShotExample(
            request = "Запусти погоду",
            params = mapOf("target" to "/System/Applications/Weather.app")
        ),
        FewShotExample(
            request = "Открой загрузки",
            params = mapOf("target" to "Downloads")
        ),
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Operation status")
        )
    )

    override fun invoke(input: Input): String {
        val fixedPath = input.target.replace("\$HOME", System.getenv("HOME"))
        return try {
            when {
                fixedPath.contains('/') -> {
                    val isDir = !fixedPath.endsWith(".app") && File(fixedPath).isDirectory
                    if (isDir) {
                        bash.sh("""open -R "$fixedPath"""")
                    } else {
                        bash.sh("""open "$fixedPath"""")
                        "Done"
                    }
                }
                fixedPath.contains('.') -> {
                    bash.sh("""open -b $fixedPath""")
                    "Done"
                }
                else -> {
                    ToolOpenFolder(bash).invoke(ToolOpenFolder.Input(File(fixedPath).name))
                    "Done"
                }
            }
        } catch (e: Exception) {
            l.error("Error opening '$fixedPath': ${e.message}")
            ToolOpenFolder(bash).invoke(ToolOpenFolder.Input(File(fixedPath).name))
        }
    }
}

fun main() {
    val result = ToolOpenFolder(ToolRunBashCommand).invoke(ToolOpenFolder.Input("/System/Applcications/Weather.app"))
    println(result)
}
