package ru.souz.agent

import kotlin.test.Test
import kotlin.test.assertEquals

class AgentIdTest {
    @Test
    fun `skills graph uses a stable persisted id`() {
        assertEquals("skills", AgentId.SKILLS_GRAPH.storageValue)
        assertEquals(AgentId.SKILLS_GRAPH, AgentId.fromStorageValue("skills"))
        assertEquals(AgentId.SKILLS_GRAPH, AgentId.fromStorageValue("SKILLS_GRAPH"))
    }

    @Test
    fun `classic graph remains the default`() {
        assertEquals(AgentId.GRAPH, AgentId.default)
        assertEquals(AgentId.GRAPH, AgentId.fromStorageValue("unknown"))
    }
}
