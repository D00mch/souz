package ru.gigadesk.server

/**
 * Interface for processing agent requests.
 * This is the bridge between the local server and the core AI logic.
 */
interface AgentNode {
    /**
     * Processes a text request and returns a response.
     *
     * @param input The text input from the mobile companion app.
     * @return The processed response text.
     * @throws Exception if processing fails.
     */
    suspend fun processRequest(input: String): String
}
