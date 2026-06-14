package ru.souz.ui.graphlog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import ru.souz.agent.session.GraphSession
import ru.souz.agent.session.GraphStepRecord

class GraphProcessingTest {

    @Test
    fun `linear session creates nodes and edges`() {
        val result = processSessionData(
            session = sampleSession("linear", "Node Start", "Node Middle", "Node End"),
            collapsedSubgraphs = emptySet(),
        )

        assertEquals(listOf("Node Start", "Node Middle", "Node End"), result.nodes.keys.toList())
        assertEquals(2, result.edges.size)
        assertEquals("Node Start", result.edges[0].fromId)
        assertEquals("Node Middle", result.edges[0].toId)
        assertEquals("Node Middle", result.edges[1].fromId)
        assertEquals("Node End", result.edges[1].toId)
    }

    @Test
    fun `repeated node increments visit count`() {
        val result = processSessionData(
            session = sampleSession("repeat", "Node Start", "Node Tool", "Node Tool", "Node End"),
            collapsedSubgraphs = emptySet(),
        )

        assertEquals(2, result.nodes.getValue("Node Tool").visitCount)
        assertEquals(2, result.nodes.getValue("Node Tool").steps.size)
    }

    @Test
    fun `collapsed subgraph groups matching nodes`() {
        val result = processSessionData(
            session = sampleSession("collapsed", "Agent::Classify", "Agent::Tools", "Node End"),
            collapsedSubgraphs = setOf("Agent"),
        )

        assertTrue("Agent" in result.nodes)
        assertTrue("Agent::Classify" !in result.nodes)
        assertTrue("Agent::Tools" !in result.nodes)
        assertEquals(2, result.nodes.getValue("Agent").visitCount)
        assertEquals(1, result.edges.size)
        assertEquals("Agent", result.edges.single().fromId)
        assertEquals("Node End", result.edges.single().toId)
    }

    private fun sampleSession(id: String, vararg nodeNames: String): GraphSession =
        GraphSession(
            id = id,
            startTime = 100L,
            endTime = 200L,
            initialInput = "hello",
            steps = nodeNames.mapIndexed { index, nodeName ->
                GraphStepRecord(
                    stepIndex = index,
                    nodeName = nodeName,
                    timestamp = 100L + index,
                    inputSummary = "input $index",
                    outputSummary = "output $index",
                    data = "{}",
                )
            },
        )
}
