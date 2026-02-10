package ru.gigadesk.tool.desktop

import ru.gigadesk.tool.*
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
        val suffix = if (input.nameSuffix.isNotBlank()) "_${input.nameSuffix}" else ""
        val fileName = "Screenshot_$timestamp$suffix.png"
        val homeDir = System.getProperty("user.home")
        val desktopPath = "$homeDir/Desktop/$fileName"
        
        // Ensure path is safe (basic check)
        if (fileName.contains("/") || fileName.contains("\\")) {
             throw IllegalArgumentException("Invalid filename characters")
        }

        bash.sh("screencapture -x \"$desktopPath\"")
        
        return "Screenshot saved to: $desktopPath"
    }
}
