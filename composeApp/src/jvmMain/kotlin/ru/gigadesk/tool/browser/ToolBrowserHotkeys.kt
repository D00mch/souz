package ru.gigadesk.tool.browser

import ru.gigadesk.keys.HotKey
import ru.gigadesk.keys.Keys
import ru.gigadesk.tool.*

class ToolBrowserHotkeys(private val keys: Keys) : ToolSetup<ToolBrowserHotkeys.Input> {

    data class Input(
        @InputParamDescription("Select a browser hotkey to press")
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
        keys.press(input.hotKey)
        return "Pressed '${input.hotKey}'"
    }
}
