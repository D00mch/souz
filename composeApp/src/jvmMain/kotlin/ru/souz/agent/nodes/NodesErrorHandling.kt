package ru.souz.agent.nodes

import org.jetbrains.compose.resources.getString
import org.slf4j.LoggerFactory
import ru.souz.agent.engine.AgentContext
import ru.souz.agent.engine.Node
import ru.souz.giga.GigaResponse
import souz.composeapp.generated.resources.Res
import souz.composeapp.generated.resources.*

/**
 * Nodes for handling LLM chat errors.
 */
class NodesErrorHandling {
    private val l = LoggerFactory.getLogger(NodesErrorHandling::class.java)

    /**
     * Converts [GigaResponse.Chat.Error] to a user-facing response string.
     *
     * Resets history when the error indicates the context was too large.
     */
    fun chatErrorToFinish(name: String = "Chat.Error"): Node<GigaResponse.Chat, String> =
        Node(name) { ctx: AgentContext<GigaResponse.Chat> ->
            val error = ctx.input as GigaResponse.Chat.Error
            if (error.status == PAYLOAD_TOO_LARGE_STATUS) {
                l.info("Resetting history due to a large object in it")
                ctx.map(history = emptyList()) { getString(Res.string.error_agent_context_reset) }
            } else if (error.message.isKtorRequestTimeoutMessage()) {
                l.info("LLM request timed out. Returning friendly timeout message")
                ctx.map { getString(Res.string.error_agent_timeout) }
            } else {
                l.info("Unknown error. Status: ${error.status}. Message: ${error.message}")
                ctx.map { error.message }
            }
        }
}

private fun String.isKtorRequestTimeoutMessage(): Boolean {
    val normalized = lowercase()
    return normalized.contains("request timeout has expired") || normalized.contains("request_timeout=")
}

private const val PAYLOAD_TOO_LARGE_STATUS = 413
