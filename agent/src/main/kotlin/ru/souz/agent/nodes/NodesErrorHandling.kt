package ru.souz.agent.nodes

import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import ru.souz.agent.graph.Node
import ru.souz.agent.state.AgentContext
import ru.souz.agent.spi.AgentErrorMessages
import ru.souz.llms.LLMResponse
import ru.souz.llms.restJsonMapper

/**
 * Nodes for handling LLM chat errors.
 */
internal class NodesErrorHandling(
    private val errorMessages: AgentErrorMessages,
) {
    private val l = LoggerFactory.getLogger(NodesErrorHandling::class.java)

    /**
     * Converts [LLMResponse.Chat.Error] to a user-facing response string.
     *
     * Resets history when the error indicates the context was too large.
     */
    fun chatErrorToFinish(name: String = "Chat.Error"): Node<LLMResponse.Chat, String> =
        Node(name) { ctx: AgentContext<LLMResponse.Chat> ->
            val error = ctx.input as LLMResponse.Chat.Error
            if (error.status == PAYLOAD_TOO_LARGE_STATUS) {
                l.info("Resetting history due to a large object in it")
                val message = errorMessages.contextReset()
                ctx.map(history = emptyList()) { message }
            } else if (error.status == PAYMENT_REQUIRED) {
                showPaymentError(error, ctx)
            } else if (error.message.isKtorRequestTimeoutMessage()) {
                l.info("LLM request timed out. Returning friendly timeout message")
                val message = errorMessages.timeout()
                ctx.map { message }
            } else {
                l.info("Unknown error. Status: ${error.status}. Message: ${error.message}")
                ctx.map { error.message }
            }
        }

    private suspend fun showPaymentError(
        error: LLMResponse.Chat.Error,
        ctx: AgentContext<LLMResponse.Chat>
    ): AgentContext<String> {
        l.info("No money left, ${error.message}")
        val isMessageJson: Boolean = try {
            restJsonMapper.readValue<Map<String, Any>>(error.message)
            true
        } catch (_: Exception) {
            false
        }
        val noMoneyMessage = errorMessages.noMoney()
        return ctx.map(history = emptyList()) {
            if (isMessageJson) {
                buildString {
                    appendLine(noMoneyMessage)
                    appendLine()
                    appendLine("```json")
                    appendLine(error.message)
                    append("```")
                }
            } else {
                noMoneyMessage + ":\n ${error.message}"
            }
        }
    }
}

private fun String.isKtorRequestTimeoutMessage(): Boolean {
    val normalized = lowercase()
    return normalized.contains("request timeout has expired") || normalized.contains("request_timeout=")
}

private const val PAYLOAD_TOO_LARGE_STATUS = 413
private const val PAYMENT_REQUIRED = 402
