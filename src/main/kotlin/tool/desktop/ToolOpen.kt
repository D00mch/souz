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
    override val description: String = "Opens apps, files or folders"

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
        val fixed = input.target.replace("\$HOME", System.getenv("HOME"))
        return try {
            when {
                fixed.contains('/') -> {
                    val file = File(fixed)
                    if (file.isDirectory) {
                        ToolOpenFolder(bash).invoke(ToolOpenFolder.Input(File(fixed).name))
                    } else {
                        bash.sh("""open "${fixed}"""")
                        "Done"
                    }
                }
                fixed.contains('.') -> {
                    bash.sh("""open -b $fixed""")
                    "Done"
                }
                else -> {
                    ToolOpenFolder(bash).invoke(ToolOpenFolder.Input(File(fixed).name))
                }
            }
        } catch (e: Exception) {
            l.error("Error opening '$fixed': ${e.message}")
            ToolOpenFolder(bash).invoke(ToolOpenFolder.Input(File(fixed).name))
        }
    }
}

fun main() {
    val tool = ToolOpen(ToolRunBashCommand)
    println(tool.invoke(ToolOpen.Input("com.apple.Safari")))

}
