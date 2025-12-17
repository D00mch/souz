package ru.gigadesk.tool.browser

import ru.gigadesk.tool.FewShotExample
import ru.gigadesk.tool.ReturnParameters
import ru.gigadesk.tool.ReturnProperty
import ru.gigadesk.tool.ToolRunBashCommand
import ru.gigadesk.tool.ToolSetup
import ru.gigadesk.tool.application.ToolOpen

class ToolOpenDefaultBrowser(private val bash: ToolRunBashCommand) : ToolSetup<ToolOpenDefaultBrowser.Input> {
    object Input

    override val name: String = "OpenDefaultBrowser"
    override val description: String = "Opens the default browser application determined by the system settings"

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Запусти браузер",
            params = emptyMap()
        )
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Operation status")
        )
    )

    override fun invoke(input: Input): String {
        val browserType = bash.detectDefaultBrowser()
        val target = when (browserType) {
            BrowserType.SAFARI -> "/Applications/Safari.app"
            BrowserType.CHROME -> "/Applications/Google Chrome.app"
            else -> "/Applications/Safari.app"
        }

        ToolOpen(bash).invoke(ToolOpen.Input(target))
        return "Done"
    }
}
