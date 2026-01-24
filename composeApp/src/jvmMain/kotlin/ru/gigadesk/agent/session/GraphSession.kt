package ru.gigadesk.agent.session

import kotlinx.serialization.Serializable

/**
 * Запись одного шага выполнения графа
 */
@Serializable
data class GraphStepRecord(
    val stepIndex: Int,
    val nodeName: String,
    val timestamp: Long,
    val inputSummary: String,
    val outputSummary: String? = null,
)

/**
 * Сессия работы графового агента
 */
@Serializable
data class GraphSession(
    val id: String,
    val startTime: Long,
    val endTime: Long? = null,
    val initialInput: String,
    val steps: List<GraphStepRecord>,
)
