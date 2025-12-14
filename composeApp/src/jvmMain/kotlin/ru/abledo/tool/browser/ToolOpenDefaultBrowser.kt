package ru.abledo.tool.browser

import ru.abledo.tool.FewShotExample
import ru.abledo.tool.ReturnParameters
import ru.abledo.tool.ReturnProperty
import ru.abledo.tool.ToolRunBashCommand
import ru.abledo.tool.ToolSetup
import ru.abledo.tool.application.ToolOpen

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
