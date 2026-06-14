package ru.souz.ui.common

import org.slf4j.Logger
import ru.souz.ui.host.ExternalLinkOpener
import java.awt.Desktop
import java.net.URI

class DesktopExternalLinkOpener : ExternalLinkOpener {
    override fun open(url: String): Result<Unit> = runCatching {
        browse(url)
    }
}

fun openProviderLink(url: String, logger: Logger) {
    DesktopExternalLinkOpener()
        .open(url)
        .onFailure { logger.warn("Failed to open provider link: $url", it) }
}

private fun browse(url: String) {
    if (!Desktop.isDesktopSupported()) error("Desktop browsing is not supported")
    val desktop = Desktop.getDesktop()
    if (!desktop.isSupported(Desktop.Action.BROWSE)) error("Desktop browsing action is not supported")
    desktop.browse(URI(url))
}
