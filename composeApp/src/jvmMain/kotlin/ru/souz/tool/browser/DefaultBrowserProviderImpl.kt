package ru.souz.tool.browser

import ru.souz.agent.spi.DefaultBrowserProvider
import ru.souz.tool.ToolRunBashCommand

object DefaultBrowserProviderImpl : DefaultBrowserProvider {
    override fun defaultBrowserDisplayName(): String? =
        runCatching { ToolRunBashCommand.detectDefaultBrowser().prettyName }.getOrNull()
}
