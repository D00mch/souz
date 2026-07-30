package ru.souz.di

import ru.souz.agent.spi.AgentRuntimeEnvironment
import java.time.ZoneId
import java.util.Locale

internal class DesktopAgentRuntimeEnvironment(
    override val locale: Locale,
) : AgentRuntimeEnvironment {
    override val zoneId: ZoneId
        get() = ZoneId.systemDefault()
}
