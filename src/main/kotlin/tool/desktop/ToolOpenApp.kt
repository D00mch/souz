package com.dumch.tool.desktop

import com.dumch.tool.*
import org.slf4j.LoggerFactory

class ToolOpenApp(private val bash: ToolRunBashCommand) : ToolSetup<ToolOpenApp.Input> {
    private val l = LoggerFactory.getLogger(ToolOpenApp::class.java)

    data class Input(
        @InputParamDescription("The name of the app to open, e.g., 'Safari', 'Calendar', 'Telegram'")
        val appName: String
    )

    override val name: String = "OpenApp"
    override val description: String = "Opens the given app"

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Запусти калькулятор",
            params = mapOf("appName" to "Calculator")
        )
    )
    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Operation status")
        )
    )

    override fun invoke(input: Input): String {
        try {
            bash.sh("""open -a "${input.appName}"""")
        } catch (e: Exception) {
            return "Error in ToolOpenApp: ${e.message}"
                .also { l.error(it, e) }
        }
        return "Done"
    }
}

fun main() {
    val tool = ToolOpenApp(ToolRunBashCommand)
    println(tool.invoke(ToolOpenApp.Input("Finder")))
}