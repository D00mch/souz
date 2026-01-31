package ru.gigadesk.server

/**
 * Request DTO for agent commands.
 */
data class AgentRequest(val text: String)

/**
 * Message DTO for agent conversation history.
 */
data class AgentMessage(
    val role: String,
    val content: String,
    val name: String? = null
)

sealed class AgentResponse {

    data class Success(
        val result: String,
        val history: List<AgentMessage> = emptyList()
    ) : AgentResponse()

    data class Error(
        val error: String
    ) : AgentResponse()
}