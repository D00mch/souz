package ru.souz.tool.desktop

import ru.souz.tool.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ToolTakeScreenshot(
    private val bash: ToolRunBashCommand,
) : ToolSetup<ToolTakeScreenshot.Input> {

    data class Input(
        @InputParamDescription("Optional name suffix for the screenshot file")
        val nameSuffix: String = ""
    )

    override val name: String = "TakeScreenshot"
    override val description: String = "Takes an instant screenshot of the main screen and saves it to the Desktop. " +
            "Returns the path to the saved file."

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Сделай скриншот",
            params = mapOf("nameSuffix" to "")
        ),
        FewShotExample(
            request = "Заскринь экран",
            params = mapOf("nameSuffix" to "evidence")
        ),
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Path to the saved screenshot")
        )
    )

    override fun invoke(input: Input): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(Date())
        
        // Strict sanitization: ensure suffix only contains alphanumeric characters, underscores, or hyphens
        val safeSuffix = if (input.nameSuffix.isNotBlank()) {
            val sanitized = input.nameSuffix.replace(Regex("[^a-zA-Z0-9_-]"), "")
            if (sanitized.isBlank()) "" else "_$sanitized"
        } else ""

        val fileName = "Screenshot_$timestamp$safeSuffix.png"
        val homeDir = System.getProperty("user.home")
        val desktopPath = "$homeDir/Desktop/$fileName"
        
        // Use argument passing to avoid shell injection
        bash.sh("screencapture -x \"$1\"", desktopPath)
        
        return "Screenshot saved to: $desktopPath"
    }
}
