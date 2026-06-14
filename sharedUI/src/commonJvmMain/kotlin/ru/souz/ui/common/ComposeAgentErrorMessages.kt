package ru.souz.ui.common

import org.jetbrains.compose.resources.getString
import ru.souz.agent.spi.AgentErrorMessages
import souz.sharedui.generated.resources.Res
import souz.sharedui.generated.resources.error_agent_context_reset
import souz.sharedui.generated.resources.error_agent_no_money
import souz.sharedui.generated.resources.error_agent_timeout

class ComposeAgentErrorMessages : AgentErrorMessages {
    override suspend fun contextReset(): String = getString(Res.string.error_agent_context_reset)

    override suspend fun timeout(): String = getString(Res.string.error_agent_timeout)

    override suspend fun noMoney(): String = getString(Res.string.error_agent_no_money)
}