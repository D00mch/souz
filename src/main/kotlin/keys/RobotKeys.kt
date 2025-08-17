package com.dumch.keys

import java.awt.*
import java.awt.datatransfer.*
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.im.InputContext
import java.net.URI
import java.util.*

object MrRobot {

    private val robot = Robot()

    /* ---------- KEYBOARD ---------- */

    private val keyEvents: Map<String, Int> = buildMap {
        ('a'..'z').forEach { put("$it", KeyEvent.getExtendedKeyCodeForChar(it.code)) }
        ('0'..'9').forEach { put("$it", KeyEvent.getExtendedKeyCodeForChar(it.code)) }
        putAll(
            mapOf(
                "cmd" to KeyEvent.VK_META,
                "meta" to KeyEvent.VK_META,
                "shift" to KeyEvent.VK_SHIFT,
                "alt" to KeyEvent.VK_ALT,
                "ctrl" to KeyEvent.VK_CONTROL,
                "esc" to KeyEvent.VK_ESCAPE,
                "enter" to KeyEvent.VK_ENTER,
                "back" to KeyEvent.VK_BACK_SPACE,
                "backspace" to KeyEvent.VK_BACK_SPACE,
                "tab" to KeyEvent.VK_TAB,
                "caps" to KeyEvent.VK_CAPS_LOCK,
                "space" to KeyEvent.VK_SPACE,
                "win" to KeyEvent.VK_WINDOWS,
                "page-down" to KeyEvent.VK_PAGE_DOWN,
                "page-up" to KeyEvent.VK_PAGE_UP,
                "home" to KeyEvent.VK_HOME,
                "end" to KeyEvent.VK_END,
                "f1" to KeyEvent.VK_F1,
                "f2" to KeyEvent.VK_F2,
                "f3" to KeyEvent.VK_F3,
                "f4" to KeyEvent.VK_F4,
                "f5" to KeyEvent.VK_F5,
                "f6" to KeyEvent.VK_F6,
                "f7" to KeyEvent.VK_F7,
                "f8" to KeyEvent.VK_F8,
                "f9" to KeyEvent.VK_F9,
                "f10" to KeyEvent.VK_F10,
                "f11" to KeyEvent.VK_F11,
                "f12" to KeyEvent.VK_F12,
                "left" to KeyEvent.VK_LEFT,
                "right" to KeyEvent.VK_RIGHT,
                "up" to KeyEvent.VK_UP,
                "down" to KeyEvent.VK_DOWN
            )
        )
    }

    fun sleep(ms: Int) = robot.delay(ms)

    fun type(vararg keys: Any, delay: Int = 40) {
        for (key in keys) {
            type(key, delay)
        }
    }

    fun type(key: Any, delay: Int = 40) {
        val code = when (key) {
            is Int -> key
            is Char -> KeyEvent.getExtendedKeyCodeForChar(key.code)
            is String -> keyEvents[key.lowercase()] ?: error("Unknown key $key")
            else -> error("Unsupported key type $key. Only Int, Char, or String are allowed")
        }
        robot.keyPress(code)
        robot.delay(delay)
        robot.keyRelease(code)
    }

    fun hotKeys(
        vararg keys: Any,
        delayBetweenPress: Int = 10,
        delayBeforeRelease: Int = 100,
    ) {
        val codes = keys.map {
            when (it) {
                is Int -> it
                is Char -> KeyEvent.getExtendedKeyCodeForChar(it.code)
                is String -> keyEvents[it.lowercase()] ?: error("Unknown key $it")
                else -> error("Unsupported key type $it. Don not pass numbers, just use chars, strings or ints")
            }
        }
        codes.forEach {
            robot.keyPress(it)
            robot.delay(delayBetweenPress)
        }
        robot.delay(delayBeforeRelease)
        codes.asReversed().forEach(robot::keyRelease)
    }

    fun typeText(
        s: String,
        delayBeforePress: Int = 70,
        delayBeforeRelease: Int = 0
    ) {
        s.toByteArray().forEach { b ->
            var code = b.toInt() and 0xFF
            if (code in 97..122) code -= 32          // a–z -> A–Z

            when (code) {
                33  -> hotKeys(KeyEvent.VK_SHIFT, KeyEvent.VK_1)
                34  -> hotKeys(KeyEvent.VK_SHIFT, KeyEvent.VK_QUOTE)
                35  -> hotKeys(KeyEvent.VK_SHIFT, KeyEvent.VK_3)
                36  -> hotKeys(KeyEvent.VK_SHIFT, KeyEvent.VK_4)
                37  -> hotKeys(KeyEvent.VK_SHIFT, KeyEvent.VK_5)
                38  -> hotKeys(KeyEvent.VK_SHIFT, KeyEvent.VK_7)
                39  -> type(KeyEvent.VK_QUOTE)
                40  -> hotKeys(KeyEvent.VK_SHIFT, KeyEvent.VK_9)
                41  -> hotKeys(KeyEvent.VK_SHIFT, KeyEvent.VK_0)
                42  -> hotKeys(KeyEvent.VK_SHIFT, KeyEvent.VK_8)
                91  -> type(KeyEvent.VK_OPEN_BRACKET)
                93  -> type(KeyEvent.VK_CLOSE_BRACKET)
                94  -> hotKeys(KeyEvent.VK_SHIFT, KeyEvent.VK_6)
                96  -> type(KeyEvent.VK_BACK_QUOTE)
                58  -> hotKeys(KeyEvent.VK_SHIFT, KeyEvent.VK_SEMICOLON)
                63  -> hotKeys(KeyEvent.VK_SHIFT, KeyEvent.VK_SLASH)
                64  -> hotKeys(KeyEvent.VK_SHIFT, KeyEvent.VK_2)
                123 -> hotKeys(KeyEvent.VK_SHIFT, KeyEvent.VK_OPEN_BRACKET)
                125 -> hotKeys(KeyEvent.VK_SHIFT, KeyEvent.VK_CLOSE_BRACKET)
                126 -> hotKeys(KeyEvent.VK_SHIFT, KeyEvent.VK_BACK_QUOTE)
                else -> {
                    robot.delay(delayBeforePress)
                    robot.keyPress(code)
                    robot.delay(delayBeforeRelease)
                    robot.keyRelease(code)
                }
            }
        }
    }


    fun keyName(code: Int): String = KeyEvent.getKeyText(code)

    fun keyboardMap(): SortedMap<Int, String> = TreeMap<Int, String>().apply {
        for (i in 0..100_000) {
            val txt = keyName(i)
            if (!txt.startsWith("Unknown")) put(i, txt)
        }
    }

    /* ---------- SCREEN ---------- */

    fun screenSize(): Dimension = Toolkit.getDefaultToolkit().screenSize
    fun currentLayout() = InputContext.getInstance().locale

    /* ---------- MOUSE ---------- */

    enum class MouseButton(val mask: Int) {
        LEFT(InputEvent.BUTTON1_DOWN_MASK), MIDDLE(InputEvent.BUTTON2_DOWN_MASK), RIGHT(
            InputEvent.BUTTON3_DOWN_MASK
        )
    }

    fun click(btn: MouseButton = MouseButton.LEFT, delay: Int = 70) {
        robot.mousePress(btn.mask)
        robot.delay(delay)
        robot.mouseRelease(btn.mask)
    }

    fun mousePos(): Point = MouseInfo.getPointerInfo().location
    fun move(x: Int, y: Int) = robot.mouseMove(x, y)
    fun move(p: Point) = move(p.x, p.y)
    fun scroll(amount: Int) = robot.mouseWheel(amount)

    /* ---------- COLOR ---------- */

    data class ARGB(val a: Int, val r: Int, val g: Int, val b: Int)

    private fun Int.toARGB() = ARGB(this ushr 24 and 0xFF, this ushr 16 and 0xFF, this ushr 8 and 0xFF, this and 0xFF)
    fun pixelColor(x: Int, y: Int): Color = robot.getPixelColor(x, y)
    fun pixelARGB(x: Int, y: Int): ARGB = pixelColor(x, y).rgb.toARGB()

    fun capture(
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        transform: (Int) -> Int = { it }
    ): List<List<Int>> {
        val img = robot.createScreenCapture(Rectangle(x, y, w, h))
        return List(h) { yy -> List(w) { xx -> transform(img.getRGB(xx, yy)) } }
    }

    fun captureHor(x: Int, y: Int, w: Int, transform: (Int) -> Int = { it }) = capture(x, y, w, 1, transform).first()
    fun captureVer(x: Int, y: Int, h: Int, transform: (Int) -> Int = { it }) =
        capture(x, y, 1, h, transform).map { it.first() }

    /* ---------- CLIPBOARD ---------- */

    private val clipboard = Toolkit.getDefaultToolkit().systemClipboard

    fun clipboardPut(text: String) = runCatching {
        clipboard.setContents(StringSelection(text), null)
    }.onFailure {
        sleep(20); clipboard.setContents(StringSelection(text), null)
    }

    fun clipboardGet(): String? {
        val content = runCatching { clipboard.getContents(null) }
            .onFailure { sleep(20) }
            .getOrNull()
        return if (content != null && content.isDataFlavorSupported(DataFlavor.stringFlavor))
            runCatching { content.getTransferData(DataFlavor.stringFlavor) as String }.getOrNull()
        else null
    }

    /* ---------- LAUNCH ---------- */

    fun launch(uri: String) = Desktop.getDesktop().browse(URI(uri))
}

fun main() {
    MrRobot.sleep(1200)
    MrRobot.hotKeys("cmd", "shift", "c") // горячие клавиши, копирую путь к файлу
    MrRobot.type('g', 'i') // нажатие клавиш g и i
    MrRobot.typeText("What the fuck?!!!") // набор текста
}