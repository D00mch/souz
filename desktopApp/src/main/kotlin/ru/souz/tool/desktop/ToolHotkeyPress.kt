package ru.souz.tool.desktop

import ru.souz.llms.ToolInvocationMeta

import ru.souz.service.keys.HotKey
import ru.souz.service.keys.Keys
import ru.souz.tool.*

class ToolHotkeyMac(private val keys: Keys) : ToolSetup<ToolHotkeyMac.Input> {

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

    override fun invoke(input: Input, meta: ToolInvocationMeta): String {
        require(System.getProperty("os.name").contains("Mac", ignoreCase = true)) {
            "This implementation supports macOS only."
        }

        keys.press(input.hotKey)

        return "Pressed ${input.hotKey}"
    }

    override suspend fun suspendInvoke(input: Input, meta: ToolInvocationMeta): String = invoke(input, meta)
}

fun main() {
    val tool = ToolHotkeyMac(Keys())
    println(tool.invoke(ToolHotkeyMac.Input(HotKey.cancel_last_action), ToolInvocationMeta.localDefault()))
}
