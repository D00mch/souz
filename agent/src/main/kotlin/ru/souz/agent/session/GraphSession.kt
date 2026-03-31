package ru.souz.agent.session

/**
 * One step (node to node transition) in a [ru.souz.agent.graph.Graph]
 * @property data steps data in Json
 */
data class GraphStepRecord(
    val stepIndex: Int,
    val nodeName: String,
    val timestamp: Long,
    val inputSummary: String,
    val outputSummary: String? = null,
    val data: String,
    val addedHistory: String? = null
)

/**
 * [ru.souz.agent.graph.Graph] execution session.
 */
data class GraphSession(
    val id: String,
    val startTime: Long,
    val endTime: Long? = null,
    val initialInput: String,
    val steps: List<GraphStepRecord>,
)
