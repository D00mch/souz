package ru.souz.tool.browser

import ru.souz.tool.FewShotExample
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolRunBashCommand
import ru.souz.tool.ToolSetup
import ru.souz.tool.application.ToolOpen
import ru.souz.tool.files.FilesToolUtil

class ToolOpenDefaultBrowser(
    private val bash: ToolRunBashCommand,
    private val filesToolUtil: FilesToolUtil,
) : ToolSetup<ToolOpenDefaultBrowser.Input> {
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

        ToolOpen(bash, filesToolUtil).invoke(ToolOpen.Input(target))
        return "Done"
    }
}
