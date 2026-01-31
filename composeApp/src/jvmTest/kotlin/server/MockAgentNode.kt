package server

import kotlinx.coroutines.delay
import ru.gigadesk.server.AgentMessage
import ru.gigadesk.server.AgentNode
import ru.gigadesk.server.AgentResult

/**
 * Mock implementation of [ru.gigadesk.server.AgentNode] for testing purposes.
 * Echoes the input with a simulated processing delay and mock history.
 */
class MockAgentNode : AgentNode {
    override suspend fun processRequest(input: String): AgentResult {
        delay(100)

        val mockHistory = listOf(
            AgentMessage(role = "user", content = input),
            AgentMessage(role = "assistant", content = "Mock response to: $input")
        )

        return AgentResult(
            response = "Mock response to: $input",
            history = mockHistory
        )
    }
}