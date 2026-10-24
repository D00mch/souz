package ru.souz.agent.spi

import java.time.ZoneId
import java.util.Locale

/**
 * Supplies request-scoped locale and time-zone context for agent execution.
 */
interface AgentRuntimeEnvironment {
    val locale: Locale
    val zoneId: ZoneId
}

object SystemAgentRuntimeEnvironment : AgentRuntimeEnvironment {
    override val locale: Locale
        get() = Locale.getDefault()

    override val zoneId: ZoneId
        get() = ZoneId.systemDefault()
}
