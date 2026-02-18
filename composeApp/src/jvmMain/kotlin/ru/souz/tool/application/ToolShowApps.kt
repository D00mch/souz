package ru.souz.tool.application

import com.fasterxml.jackson.annotation.JsonProperty
import org.kodein.di.DI
import org.kodein.di.instance
import ru.souz.di.mainDiModule
import ru.souz.giga.gigaJsonMapper
import ru.souz.tool.*
import ru.souz.tool.files.FilesToolUtil

class ToolShowApps(
    private val filesToolUtil: FilesToolUtil,
    private val bash: ToolRunBashCommand = ToolRunBashCommand,
) : ToolSetup<ToolShowApps.Input> {

    data class Input(
        @InputParamDescription("What apps to show")
        val state: AppState,
    )

    enum class AppState { installed, running, }

    override val name: String = "ShowApps"
    override val description: String = "Shows installed or running (launched) apps"
    override val fewShotExamples: List<FewShotExample> = listOf(
        FewShotExample(
            request = "Покажи список открытых приложений",
            params = mapOf("state" to AppState.running)
        ),
        FewShotExample(
            request = "Покажи список установленных приложений",
            params = mapOf("state" to AppState.installed)
        ),
    )
    override val returnParameters: ReturnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", """JSON of apps like:
```json
[{
  "app-bundle-id" : "com.google.Chrome",
  "app-name" : "Google Chrome",
  "app-pid" : 54353
}]
```
The "app-pid" only returned for running apps with `${AppState.running}` input.
            """.trimMargin())
        )
    )

    override fun invoke(input: Input): String = when (input.state) {
        AppState.installed -> {
            val script = filesToolUtil.resourceAsText("scripts/show_installed_apps.sh")
            val appsLines = ToolRunBashCommand.sh(script)
                .lines()
                .map { line ->
                    val (bundleId, appName) = line.split(" ", limit = 2)
                    Result(bundleId, appName)
                }
            gigaJsonMapper.writeValueAsString(appsLines)
        }

        AppState.running -> {
            val result = bash.apple(
               """
               tell application "System Events"
                set appNames to name of (application processes where background only is false)
               end tell
               set AppleScript's text item delimiters to linefeed
               set out to appNames as text
               set AppleScript's text item delimiters to ""
               return out
               """.trimIndent()
            )
            result.lines().joinToString(prefix = "[", postfix = "]", separator = ",") { it }
        }
    }
}

private data class Result(
    @field:JsonProperty("app-bundle-id") val appBundleId: String,
    @field:JsonProperty("app-name") val appName: String,
)

fun main() {
    val di = DI.invoke { import(mainDiModule) }
    val filesToolUtil: FilesToolUtil by di.instance()

    val tool = ToolShowApps(filesToolUtil)
    val result = tool.invoke(ToolShowApps.Input(ToolShowApps.AppState.running))
    println(result)
}
