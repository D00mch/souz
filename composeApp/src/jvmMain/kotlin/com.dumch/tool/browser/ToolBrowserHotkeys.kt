package com.dumch.tool.browser

import com.dumch.keys.*
import com.dumch.tool.*
import java.lang.Thread.sleep

class ToolBrowserHotkeys : ToolSetup<ToolBrowserHotkeys.Input> {
    private val cg = CoreGraphics.INSTANCE
    private val cf = CoreFoundation.INSTANCE

    enum class HotKey {
        new_tab,
        open_just_closed_tab,
        close_tab,
        cancel_last_action,
        full_screen_toggle,
        scroll_down,
        arrow_left,
        arrow_right,
        arrow_up,
        arrow_down,
        page_up,
        page_down,
    }

    data class Input(
        @InputParamDescription(
            """Select a browser hotkey to press. Options:
            \"new_tab\"               -> cmd+t
            \"open_just_closed_tab\"   -> cmd+shift+t
            \"close_tab\"             -> cmd+w
            \"cancel_last_action\"    -> cmd+z
            \"full_screen_toggle\"    -> ctrl+cmd+f
            \"scroll_down\"           -> space
            \"arrow_left\"            -> left arrow
            \"arrow_right\"           -> right arrow
            \"arrow_up\"              -> up arrow
            \"arrow_down\"            -> down arrow
            \"page_up\"              -> page up
            \"page_down\"            -> page down"""
        )
        val hotKey: HotKey,
    )

    override val name = "BrowserHotkeys"
    override val description = "Press a browser related hotkey like \"new_tab\" or \"scroll_down\""
    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Открой новую вкладку",
            params = mapOf("hotKey" to HotKey.new_tab)
        ),
        FewShotExample(
            request = "Пролистай страницу вниз",
            params = mapOf("hotKey" to HotKey.scroll_down)
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
        execute(input.hotKey)
        return "Pressed ${'$'}{input.hotKey}"
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

    private fun execute(key: HotKey) {
        when (key) {
            HotKey.new_tab -> combo(VK.T, VK.CMD)
            HotKey.open_just_closed_tab -> combo(VK.T, VK.CMD, VK.SHIFT)
            HotKey.close_tab -> combo(VK.W, VK.CMD)
            HotKey.cancel_last_action -> combo(VK.Z, VK.CMD)
            HotKey.full_screen_toggle -> combo(VK.F, VK.CMD, VK.CTRL)
            HotKey.scroll_down -> press(VK.SPACE)
            HotKey.arrow_left -> press(VK.LEFT)
            HotKey.arrow_right -> press(VK.RIGHT)
            HotKey.arrow_up -> press(VK.UP)
            HotKey.arrow_down -> press(VK.DOWN)
            HotKey.page_up -> press(VK.PAGE_UP)
            HotKey.page_down -> press(VK.PAGE_DOWN)
        }
    }
}
