package ru.souz.agent.spi

/**
 * Supplies the host system's default browser name.
 *
 * The agent uses this only for prompt enrichment, so the contract stays small
 * and display-oriented.
 */
fun interface DefaultBrowserProvider {
    /** Returns a human-readable browser name, or null when it cannot be resolved. */
    fun defaultBrowserDisplayName(): String?
}
