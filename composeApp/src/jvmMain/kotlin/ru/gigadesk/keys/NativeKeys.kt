@file:Suppress("unused")

package ru.gigadesk.keys

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure

@Structure.FieldOrder("x", "y")
class CGPoint : Structure, Structure.ByValue {
    @JvmField var x: Double = 0.0
    @JvmField var y: Double = 0.0
    constructor() : super()
    constructor(x: Double, y: Double) : this() {
        this.x = x
        this.y = y
    }
}

interface CoreGraphics : Library {
    fun CGEventCreateMouseEvent(
        source: Pointer?,
        mouseType: Int,
        mouseCursorPosition: CGPoint,
        mouseButton: Int
    ): Pointer

    fun CGEventPost(tap: Int, eventRef: Pointer)
    fun CGWarpMouseCursorPosition(newCursorPosition: CGPoint): Int
    fun CGEventCreateKeyboardEvent(source: Pointer?, virtualKey: Int, keyDown: Boolean): Pointer

    companion object {
        val INSTANCE: CoreGraphics by lazy {
            Native.load("CoreGraphics", CoreGraphics::class.java)
        }
    }
}

interface CoreFoundation : Library {
    fun CFRelease(ref: Pointer)

    companion object {
        val INSTANCE: CoreFoundation by lazy {
            Native.load("CoreFoundation", CoreFoundation::class.java)
        }
    }
}

object CG {
    const val kCGHIDEventTap = 0
}

object VK {
    // Modifiers
    const val CMD = 55
    const val SHIFT = 56
    const val CTRL = 59
    const val ALT = 58

    // Navigation
    const val LEFT = 123
    const val RIGHT = 124
    const val DOWN = 125
    const val UP = 126
    const val PAGE_UP = 116
    const val PAGE_DOWN = 121
    const val HOME = 115
    const val END = 119

    // Editing
    const val FORWARD_DELETE = 117
    const val BACKSPACE = 51
    const val RETURN = 36
    const val ESC = 53
    const val SPACE = 49

    // Letters
    const val T = 17
    const val Q = 12
    const val W = 13
    const val Z = 6
    const val F = 3
    const val V = 9
    const val C = 8
}
