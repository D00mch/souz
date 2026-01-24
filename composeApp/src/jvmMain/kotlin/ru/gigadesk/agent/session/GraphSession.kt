package ru.gigadesk.agent.session

/**
 * Запись одного шага выполнения графа
 */
data class GraphStepRecord(
    val stepIndex: Int,
    val nodeName: String,
    val timestamp: Long,
    val inputSummary: String,
    val outputSummary: String? = null,
    val data: String // Полные данные шага (JSON)
)

/**
 * Сессия работы графового агента
 */
data class GraphSession(
    val id: String,
    val startTime: Long,
    val endTime: Long? = null,
    val initialInput: String,
    val steps: List<GraphStepRecord>,
)
