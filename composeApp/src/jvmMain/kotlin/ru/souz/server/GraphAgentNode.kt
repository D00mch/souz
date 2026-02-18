package ru.souz.server

import kotlinx.coroutines.flow.first
import ru.souz.agent.GraphBasedAgent

/**
 * Real implementation of [AgentNode] that delegates to [GraphBasedAgent].
 * Used in production to process requests from the mobile companion app.
 */
class GraphAgentNode(
    private val graphAgent: GraphBasedAgent
) : AgentNode {
    
    override suspend fun processRequest(input: String): AgentResult {
        val response = graphAgent.execute(input)

        val context = graphAgent.currentContext.first()
        val history = context.history.map { message ->
            AgentMessage(
                role = message.role.name,
                content = message.content,
                name = message.name
            )
        }
        
        return AgentResult(response = response, history = history)
    }
}
