package com.dumch.tool.desktop

import com.dumch.image.ImageUtils
import com.dumch.keys.*
import com.dumch.tool.*

private object CG {
    const val kCGHIDEventTap = 0
    const val kCGEventLeftMouseDown = 1
    const val kCGEventLeftMouseUp   = 2
    const val kCGEventRightMouseDown = 3
    const val kCGEventRightMouseUp   = 4
    const val kCGEventOtherMouseDown = 25
    const val kCGEventOtherMouseUp   = 26
    const val kCGMouseButtonLeft   = 0
    const val kCGMouseButtonRight  = 1
    const val kCGMouseButtonCenter = 2
}

class ToolMouseClickMac : ToolSetup<ToolMouseClickMac.Input> {
    private val cg = CoreGraphics.INSTANCE
    private val cf = CoreFoundation.INSTANCE

    enum class MouseButton { left, right, middle }

    data class Input(
        @InputParamDescription("The x coordinate of the mouse click") val x: Int,
        @InputParamDescription("The y coordinate of the mouse click") val y: Int,
        @InputParamDescription("The button to click") val button: MouseButton = MouseButton.left
    )

    override val name = "MouseClick"
    override val description = "Emulates a mouse click on a specific screen area according to its coordinates from DesktopScreenShot (macOS)."
    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Нажми кнопку мыши в точке 100,200",
            params = mapOf("x" to 100, "y" to 200, "button" to MouseButton.left)
        )
    )
    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Information about the click")
        )
    )

    override fun invoke(input: Input): String {
        require(System.getProperty("os.name").contains("Mac", ignoreCase = true)) {
            "This implementation supports macOS only."
        }

        val x = input.x.toDouble() / ImageUtils.DESKTOP_SCREENSHOT_QUALITY
        val y = input.y.toDouble() / ImageUtils.DESKTOP_SCREENSHOT_QUALITY

        val (btnIdx, downType, upType) = when (input.button) {
            MouseButton.left -> Triple(CG.kCGMouseButtonLeft, CG.kCGEventLeftMouseDown, CG.kCGEventLeftMouseUp)
            MouseButton.right -> Triple(CG.kCGMouseButtonRight, CG.kCGEventRightMouseDown, CG.kCGEventRightMouseUp)
            MouseButton.middle -> Triple(CG.kCGMouseButtonCenter, CG.kCGEventOtherMouseDown, CG.kCGEventOtherMouseUp)
        }

        val pt = CGPoint(x, y)

        cg.CGWarpMouseCursorPosition(pt)

        val down = cg.CGEventCreateMouseEvent(null, downType, pt, btnIdx)
        cg.CGEventPost(CG.kCGHIDEventTap, down)
        cf.CFRelease(down)

        val up = cg.CGEventCreateMouseEvent(null, upType, pt, btnIdx)
        cg.CGEventPost(CG.kCGHIDEventTap, up)
        cf.CFRelease(up)

        return "Mouse clicked at ($x, $y) with button $btnIdx"
    }
}

fun main() {
    val tool = ToolMouseClickMac()
    println(tool.invoke(ToolMouseClickMac.Input(0, 0, ToolMouseClickMac.MouseButton.left)))
}
