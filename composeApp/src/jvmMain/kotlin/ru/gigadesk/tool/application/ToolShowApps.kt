package ru.gigadesk.tool.application

import ru.gigadesk.giga.objectMapper
import ru.gigadesk.tool.FewShotExample
import ru.gigadesk.tool.InputParamDescription
import ru.gigadesk.tool.ReturnParameters
import ru.gigadesk.tool.ReturnProperty
import ru.gigadesk.tool.ToolRunBashCommand
import ru.gigadesk.tool.ToolSetup
import ru.gigadesk.tool.files.FilesToolUtil
import com.fasterxml.jackson.annotation.JsonProperty
import ru.gigadesk.tool.desktop.ToolWindowsManager

object ToolShowApps : ToolSetup<ToolShowApps.Input> {
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
            val script = FilesToolUtil.resourceAsText("scripts/show_installed_apps.sh")
            val appsLines = ToolRunBashCommand.sh(script)
                .lines()
                .map { line ->
                    val (bundleId, appName) = line.split(" ", limit = 2)
                    Result(bundleId, appName)
                }
            objectMapper.writeValueAsString(appsLines)
        }

        AppState.running -> {
            val result = ToolWindowsManager.runAerospace(
                "list-apps", "--format",
                "{app:%{app-name},bundle:%{app-bundle-id},pid:%{app-pid}}"
            )
            result.lines().joinToString(prefix = "[", postfix = "]", separator = ",") { it }
        }
    }
}

private data class Result(
    @JsonProperty("app-bundle-id") val appBundleId: String,
    @JsonProperty("app-name") val appName: String,
)

fun main() {
    val tool = ToolShowApps
    val result = tool.invoke(ToolShowApps.Input(ToolShowApps.AppState.running))
    println(result)
}