package ru.souz.server

/**
 * Result from processing an agent request.
 * @param response The final response text.
 * @param history The conversation history showing agent reasoning.
 */
data class AgentResult(
    val response: String,
    val history: List<AgentMessage>
)

/**
 * Interface for processing agent requests.
 * This is the bridge between the local server and the core AI logic.
 */
interface AgentNode {
    /**
     * Processes a text request and returns the result with history.
     *
     * @param input The text input from the mobile companion app.
     * @return The processed result including response and history.
     * @throws Exception if processing fails.
     */
    suspend fun processRequest(input: String): AgentResult
}
