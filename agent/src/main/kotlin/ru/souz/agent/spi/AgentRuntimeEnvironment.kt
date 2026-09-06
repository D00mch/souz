package ru.souz.agent.spi

import java.time.ZoneId
import java.util.Locale

/**
 * Supplies locale, time-zone and optional browser context for agent execution.
 *
 * Desktop preserves its startup locale and reads live system preferences;
 * backend supplies request-scoped locale and time-zone values.
 */
interface AgentRuntimeEnvironment {
    val locale: Locale
    val zoneId: ZoneId
    val defaultBrowserDisplayName: String? get() = null
}

object SystemAgentRuntimeEnvironment : AgentRuntimeEnvironment {
    override val locale: Locale
        get() = Locale.getDefault()

    override val zoneId: ZoneId
        get() = ZoneId.systemDefault()
}
