package ru.souz.keys

import java.lang.Thread.sleep

enum class HotKey {
    escape,
    space,
    full_screen_toggle,
    close_app,
    cancel_last_action,
    copy,
    paste,
    find,
    enter,
    new_tab,
    open_just_closed_tab,
    close_tab,
    scroll_down,
    arrow_left,
    arrow_right,
    arrow_up,
    arrow_down,
    page_up,
    page_down,
}

class Keys(
    private val cg: CoreGraphics = CoreGraphics.INSTANCE,
    private val cf: CoreFoundation = CoreFoundation.INSTANCE,
) {

    fun press(hotKey: HotKey) {
        when (hotKey) {
            HotKey.escape -> tap(VK.ESC)
            HotKey.space -> tap(VK.SPACE)
            HotKey.full_screen_toggle -> combo(VK.F, VK.CMD, VK.CTRL)
            HotKey.close_app -> combo(VK.Q, VK.CMD)
            HotKey.cancel_last_action -> combo(VK.Z, VK.CMD)
            HotKey.copy -> combo(VK.C, VK.CMD, delayBetweenPressMs = 50, delayBeforeReleaseMs = 50)
            HotKey.paste -> combo(VK.V, VK.CMD, delayBetweenPressMs = 50, delayBeforeReleaseMs = 50)
            HotKey.find -> combo(VK.F, VK.CMD, delayBetweenPressMs = 50, delayBeforeReleaseMs = 50)
            HotKey.enter -> tap(VK.RETURN)
            HotKey.new_tab -> combo(VK.T, VK.CMD)
            HotKey.open_just_closed_tab -> combo(VK.T, VK.CMD, VK.SHIFT)
            HotKey.close_tab -> combo(VK.W, VK.CMD)
            HotKey.scroll_down -> tap(VK.SPACE)
            HotKey.arrow_left -> tap(VK.LEFT)
            HotKey.arrow_right -> tap(VK.RIGHT)
            HotKey.arrow_up -> tap(VK.UP)
            HotKey.arrow_down -> tap(VK.DOWN)
            HotKey.page_up -> tap(VK.PAGE_UP)
            HotKey.page_down -> tap(VK.PAGE_DOWN)
        }
    }

    fun tap(keyCode: Int) {
        keyDown(keyCode)
        keyUp(keyCode)
    }

    fun combo(
        keyCode: Int,
        vararg modifiers: Int,
        delayBetweenPressMs: Long = 20,
        delayBeforeReleaseMs: Long = 0,
    ) {
        modifiers.forEach {
            sleep(delayBetweenPressMs)
            keyDown(it)
        }

        sleep(delayBetweenPressMs)
        tap(keyCode)

        if (delayBeforeReleaseMs > 0) {
            sleep(delayBeforeReleaseMs)
        }

        modifiers.forEach { keyUp(it) }
    }

    private fun post(key: Int, down: Boolean) {
        val evt = cg.CGEventCreateKeyboardEvent(null, key, down)
        cg.CGEventPost(CG.kCGHIDEventTap, evt)
        cf.CFRelease(evt)
    }

    private fun keyDown(keyCode: Int) = post(keyCode, true)
    private fun keyUp(keyCode: Int) = post(keyCode, false)
}
