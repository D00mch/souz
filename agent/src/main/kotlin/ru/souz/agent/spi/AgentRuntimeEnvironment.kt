package ru.souz.agent.spi

import java.time.ZoneId
import java.util.Locale

/**
 * Supplies locale and time-zone context for one agent execution environment.
 *
 * Desktop uses JVM defaults, while backend can inject request-scoped values.
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
