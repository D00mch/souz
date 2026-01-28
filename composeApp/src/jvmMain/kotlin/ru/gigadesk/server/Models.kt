package ru.gigadesk.server

/**
 * Request DTO for agent commands.
 */
data class AgentRequest(val text: String)

/**
 * Response DTO for successful agent responses.
 */
data class AgentResponse(val result: String)

/**
 * Response DTO for error cases.
 */
data class AgentErrorResponse(val error: String)
