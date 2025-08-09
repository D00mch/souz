import com.dumch.tool.InputParamDescription
import com.dumch.tool.ToolSetup
import org.slf4j.LoggerFactory
import java.awt.Robot
import java.awt.event.InputEvent

class ToolMouseClick(private val robot: Robot = Robot()) : ToolSetup<ToolMouseClick.Input> {
    private val l = LoggerFactory.getLogger(ToolMouseClick::class.java)

    override val name: String = "MouseClick"
    override val description: String = "Clicks the mouse at the given coordinates."

    override fun invoke(input: Input): String {
        robot.mouseMove(input.x.toInt(), input.y.toInt())
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        robot.delay(100)
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        return "Mouse clicked at (${input.x}, ${input.y})"
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

fun main() {
    val tool = ToolMouseClick()
    tool.invoke(ToolMouseClick.Input("0", "0", "1"))
}