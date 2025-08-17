package com.dumch.tool.desktop

import com.dumch.keys.*
import com.dumch.tool.*
import java.lang.Thread.sleep

class ToolHotkeyMac : ToolSetup<ToolHotkeyMac.Input> {
    private val cg = CoreGraphics.INSTANCE
    private val cf = CoreFoundation.INSTANCE

    enum class HotKey {
        arrow_left,
        arrow_right,
        arrow_up,
        arrow_down,
        page_up,
        page_down,
        home,
        end,
        delete,
        backspace,
        enter,
        escape,
        space,
        full_screen_toggle,
        close_app,
        close_tab,
        open_just_closed_tab,
        cancel_last_action,
    }

    data class Input(
        @InputParamDescription(
            """Select a hotkey to press. Some combined hotkey options:
"go_to_left_screen" -> ctrl+left
"go_to_right_screen" -> ctrl+right
"full_screen_toggle" -> ctrl+cmd+f
"close_app" -> cmd+q
"close_tab" -> cmd+w
"open_just_closed_tab" -> cmd+shift+t
"cancel_last_action" -> cmd+z"""
        )
        val hotKey: HotKey,
    )

    override val name = "Hotkey"
    override val description = "Press a single hotkey like \"space\" or \"close_tab\""
    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Отмени действие",
            params = mapOf("hotKey" to HotKey.cancel_last_action)
        ),
        FewShotExample(
            request = "Доскроль страницу до низа",
            params = mapOf("hotKey" to HotKey.page_down)
        ),
        FewShotExample(
            request = "Закрой вкладку",
            params = mapOf("hotKey" to HotKey.close_tab)
        ),
        FewShotExample(
            request = "Открой последнюю закрытую вкладку",
            params = mapOf("hotKey" to HotKey.open_just_closed_tab)
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
            HotKey.arrow_left -> press(VK.LEFT)
            HotKey.arrow_right -> press(VK.RIGHT)
            HotKey.arrow_up -> press(VK.UP)
            HotKey.arrow_down -> press(VK.DOWN)
            HotKey.page_up -> press(VK.PAGE_UP)
            HotKey.page_down -> press(VK.PAGE_DOWN)
            HotKey.home -> press(VK.HOME)
            HotKey.end -> press(VK.END)
            HotKey.delete -> press(VK.FORWARD_DELETE)    // forward delete
            HotKey.backspace -> press(VK.BACKSPACE)      // label "Delete" on Mac keyboards
            HotKey.enter -> press(VK.RETURN)
            HotKey.escape -> press(VK.ESC)
            HotKey.space -> press(VK.SPACE)
            HotKey.full_screen_toggle -> combo(VK.F, VK.CMD, VK.CTRL)     // ctrl+cmd+f
            HotKey.close_app -> combo(VK.Q, VK.CMD)                       // cmd+q
            HotKey.close_tab -> combo(VK.W, VK.CMD)                       // cmd+w
            HotKey.open_just_closed_tab -> combo(VK.T, VK.CMD, VK.SHIFT)  // cmd+shift+t
            HotKey.cancel_last_action -> combo(VK.Z, VK.CMD)              // cmd+z
        }
    }
}

fun main() {
    val tool = ToolHotkeyMac()
    println(tool.invoke(ToolHotkeyMac.Input(ToolHotkeyMac.HotKey.close_tab)))
}

