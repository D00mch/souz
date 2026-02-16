package ru.gigadesk.ui.common

import org.slf4j.Logger
import java.awt.Desktop
import java.net.URI

fun openProviderLink(url: String, logger: Logger) {
    runCatching {
        if (!Desktop.isDesktopSupported()) error("Desktop browsing is not supported")
        val desktop = Desktop.getDesktop()
        if (!desktop.isSupported(Desktop.Action.BROWSE)) error("Desktop browsing action is not supported")
        desktop.browse(URI(url))
    }.onFailure { logger.warn("Failed to open provider link: $url", it) }
}
