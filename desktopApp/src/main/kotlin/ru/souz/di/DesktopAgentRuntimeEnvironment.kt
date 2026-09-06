package ru.souz.di

import ru.souz.agent.spi.AgentRuntimeEnvironment
import ru.souz.tool.ToolRunBashCommand
import ru.souz.tool.browser.detectDefaultBrowser
import ru.souz.tool.browser.prettyName
import java.time.ZoneId
import java.util.Locale

internal class DesktopAgentRuntimeEnvironment(
    override val locale: Locale,
) : AgentRuntimeEnvironment {
    override val zoneId: ZoneId
        get() = ZoneId.systemDefault()

    override val defaultBrowserDisplayName: String?
        get() = runCatching { ToolRunBashCommand.detectDefaultBrowser().prettyName }.getOrNull()
}
