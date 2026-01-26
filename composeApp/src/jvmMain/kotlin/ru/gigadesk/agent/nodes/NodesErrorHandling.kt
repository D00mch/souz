package ru.gigadesk.agent.nodes

import org.slf4j.LoggerFactory
import ru.gigadesk.agent.engine.AgentContext
import ru.gigadesk.agent.engine.Node
import ru.gigadesk.giga.GigaResponse

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
                ctx.map(history = emptyList()) { RESET_CONTENT }
            } else {
                l.info("Unknown error. Status: ${error.status}. Message: ${error.message}")
                ctx.map { error.message }
            }
        }
}

private const val PAYLOAD_TOO_LARGE_STATUS = 413
private const val RESET_CONTENT =
    "Слишком много информации. Я запутался - давай начнем заново и по чуть-чуть"
