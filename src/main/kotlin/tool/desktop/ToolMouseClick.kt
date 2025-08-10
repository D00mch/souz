package com.dumch.tool.desktop

import com.dumch.image.ImageUtils
import com.dumch.keys.*
import com.dumch.tool.InputParamDescription
import com.dumch.tool.ToolSetup
import org.slf4j.LoggerFactory

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
    private val l = LoggerFactory.getLogger(ToolMouseClickMac::class.java)
    private val cg = CoreGraphics.INSTANCE
    private val cf = CoreFoundation.INSTANCE

    override val name = "MouseClick"
    override val description = "Clicks the mouse at the given coordinates (macOS)."

    override fun invoke(input: Input): String {
        require(System.getProperty("os.name").contains("Mac", ignoreCase = true)) {
            "This implementation supports macOS only."
        }

        val x = input.x.toDouble() / ImageUtils.DESKTOP_SCREENSHOT_QUALITY
        val y = input.y.toDouble() / ImageUtils.DESKTOP_SCREENSHOT_QUALITY

        val (btnIdx, downType, upType) = when (input.button.toInt()) {
            1 -> Triple(CG.kCGMouseButtonLeft, CG.kCGEventLeftMouseDown, CG.kCGEventLeftMouseUp)
            2 -> Triple(CG.kCGMouseButtonRight, CG.kCGEventRightMouseDown, CG.kCGEventRightMouseUp)
            3 -> Triple(CG.kCGMouseButtonCenter, CG.kCGEventOtherMouseDown, CG.kCGEventOtherMouseUp)
            else -> error("button must be 1(left), 2(right) or 3(middle)")
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

    class Input(
        @InputParamDescription("The x coordinate of the mouse click") val x: String,
        @InputParamDescription("The y coordinate of the mouse click") val y: String,
        @InputParamDescription("The button to click. 1 means left, 2 means right, 3 means middle") val button: String = "1"
    )
}

fun main() {
    val tool = ToolMouseClickMac()
    println(tool.invoke(ToolMouseClickMac.Input("0", "0", "1")))
}
