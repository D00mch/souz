package ru.gigadesk.server

import kotlinx.coroutines.delay

/**
 * Mock implementation of [AgentNode] for testing purposes.
 * Echoes the input with a simulated processing delay.
 */
class MockAgentNode : AgentNode {
    override suspend fun processRequest(input: String): String {
        // Simulate some processing time
        delay(100)
        return "Mock response to: $input"
    }
}
