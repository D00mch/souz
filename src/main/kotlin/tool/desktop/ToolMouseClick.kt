import com.dumch.tool.InputParamDescription
import com.dumch.tool.ToolSetup

class ToolMouseClick : ToolSetup<ToolMouseClick.Input> {
    override val name: String = "MouseClick"
    override val description: String = "Clicks the mouse at the given coordinates."

    override fun invoke(input: Input): String {
        val command =
            """osascript -e 'tell application "System Events" to click button ${input.button} of the mouse at {${input.x}, ${input.y}}'"""
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        val exitCode = process.waitFor()

        return if (exitCode == 0) {
            "Mouse clicked at (${input.x}, ${input.y})"
        } else {
            "Failed to click (exit code: $exitCode)"
        }
    }

    class Input(
        @InputParamDescription("The x coordinate of the mouse click")
        val x: String,
        @InputParamDescription("The y coordinate of the mouse click")
        val y: String,
        @InputParamDescription("The button to click. 1 means left, 2 means right, 3 means middle")
        val button: String
    )
}