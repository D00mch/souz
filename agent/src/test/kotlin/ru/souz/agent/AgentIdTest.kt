package ru.souz.agent

import kotlin.test.Test
import kotlin.test.assertEquals

class AgentIdTest {
    @Test
    fun `legacy lua storage value falls back to graph agent`() {
        assertEquals(AgentId.GRAPH, AgentId.fromStorageValue("lua"))
    }

    @Test
    fun `graph storage value still resolves to graph agent`() {
        assertEquals(AgentId.GRAPH, AgentId.fromStorageValue("graph"))
    }
}
