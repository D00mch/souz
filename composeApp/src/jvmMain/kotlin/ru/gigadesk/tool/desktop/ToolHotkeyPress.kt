package ru.gigadesk.tool.desktop

import ru.gigadesk.keys.*
import ru.gigadesk.tool.*
import java.lang.Thread.sleep

class ToolHotkeyMac : ToolSetup<ToolHotkeyMac.Input> {
    private val cg = CoreGraphics.INSTANCE
    private val cf = CoreFoundation.INSTANCE

    enum class HotKey {
        escape,
        space,
        full_screen_toggle,
        close_app,
        cancel_last_action,
    }

    data class Input(
        @InputParamDescription(
            """Select a hotkey to press. Some combined hotkey options:
"full_screen_toggle" -> ctrl+cmd+f
"close_app" -> cmd+q
"cancel_last_action" -> cmd+z"""
        )
        val hotKey: HotKey,
    )

    override val name = "Hotkey"
    override val description = "Press a single hotkey like \"space\" or \"close_app\""
    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Отмени действие",
            params = mapOf("hotKey" to HotKey.cancel_last_action)
        ),
        FewShotExample(
            request = "Сделай прилоние на весь экран",
            params = mapOf("hotKey" to HotKey.full_screen_toggle)
        ),
        FewShotExample(
            request = "Закрой",
            params = mapOf("hotKey" to HotKey.close_app)
        ),
    )
    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Pressed keys description")
        )
    )

    override fun invoke(input: Input): String {
        require(System.getProperty("os.name").contains("Mac", ignoreCase = true)) {
            "This implementation supports macOS only."
        }

        extracted(input.hotKey)

        return "Pressed ${input.hotKey}"
    }

    private fun post(key: Int, down: Boolean) {
        val evt = cg.CGEventCreateKeyboardEvent(null, key, down)
        cg.CGEventPost(CG.kCGHIDEventTap, evt)
        cf.CFRelease(evt)
    }
    private fun keyDown(k: Int) = post(k, true)
    private fun keyUp(k: Int) = post(k, false)
    private fun press(k: Int) { keyDown(k); keyUp(k) }
    private fun combo(k: Int, vararg mods: Int) {
        mods.forEach {
            sleep(20)
            keyDown(it)
        }
        sleep(20)
        press(k)
        mods.forEach { keyUp(it) }
    }

    private fun extracted(key: HotKey) {
        when (key) {
            HotKey.escape -> press(VK.ESC)
            HotKey.space -> press(VK.SPACE)
            HotKey.full_screen_toggle -> combo(VK.F, VK.CMD, VK.CTRL)     // ctrl+cmd+f
            HotKey.close_app -> combo(VK.Q, VK.CMD)                       // cmd+q
            HotKey.cancel_last_action -> combo(VK.Z, VK.CMD)              // cmd+z
        }
    }
}

fun main() {
    val tool = ToolHotkeyMac()
    println(tool.invoke(ToolHotkeyMac.Input(ToolHotkeyMac.HotKey.cancel_last_action)))
}

