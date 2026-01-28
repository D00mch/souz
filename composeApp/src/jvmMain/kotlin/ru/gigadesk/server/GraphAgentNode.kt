package ru.gigadesk.server

import ru.gigadesk.agent.GraphBasedAgent

/**
 * Real implementation of [AgentNode] that delegates to [GraphBasedAgent].
 * Used in production to process requests from the mobile companion app.
 */
class GraphAgentNode(
    private val graphAgent: GraphBasedAgent
) : AgentNode {
    
    override suspend fun processRequest(input: String): String {
        return graphAgent.execute(input)
    }
}
