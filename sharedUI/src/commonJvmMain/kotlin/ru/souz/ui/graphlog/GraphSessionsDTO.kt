package ru.souz.ui.graphlog

import ru.souz.agent.session.GraphSession
import ru.souz.ui.VMEvent
import ru.souz.ui.VMSideEffect
import ru.souz.ui.VMState

data class GraphSessionsState(
    val isLoading: Boolean = false,
    val isOpeningSession: Boolean = false,
    val errorMessage: String? = null,
    val sessions: List<GraphSessionSummaryUi> = emptyList(),
    val selectedSession: GraphSession? = null,
) : VMState

data class GraphSessionSummaryUi(
    val id: String,
    val startTime: Long,
    val endTime: Long?,
    val initialInput: String,
    val stepsCount: Int,
    val nodePathPreview: String,
)

sealed interface GraphSessionsEvent : VMEvent {
    object Refresh : GraphSessionsEvent
    data class OpenSession(val id: String) : GraphSessionsEvent
    object BackToList : GraphSessionsEvent
}

sealed interface GraphSessionsEffect : VMSideEffect
