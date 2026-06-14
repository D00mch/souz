package ru.souz.ui.graphlog

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import org.slf4j.LoggerFactory
import ru.souz.agent.session.GraphSession
import ru.souz.agent.session.GraphSessionRepository
import ru.souz.ui.BaseViewModel

class GraphSessionsViewModel(
    override val di: DI,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BaseViewModel<GraphSessionsState, GraphSessionsEvent, GraphSessionsEffect>(), DIAware {

    private val l = LoggerFactory.getLogger(GraphSessionsViewModel::class.java)
    private val sessionRepository: GraphSessionRepository by di.instance()

    override val ioDispatchers: CoroutineDispatcher = ioDispatcher

    init {
        vmLaunch { refreshSessions() }
    }

    override fun initialState(): GraphSessionsState = GraphSessionsState()

    override suspend fun handleEvent(event: GraphSessionsEvent) {
        when (event) {
            GraphSessionsEvent.Refresh -> refreshSessions()
            is GraphSessionsEvent.OpenSession -> openSession(event.id)
            GraphSessionsEvent.BackToList -> setState { copy(selectedSession = null, errorMessage = null) }
        }
    }

    override suspend fun handleSideEffect(effect: GraphSessionsEffect) {
        l.debug("No side effects to handle: {}", effect)
    }

    private suspend fun refreshSessions() {
        setState { copy(isLoading = true, errorMessage = null) }
        val result = withContext(ioDispatchers) {
            runCatching { sessionRepository.loadAll() }
        }

        result
            .onSuccess { sessions ->
                setState {
                    copy(
                        isLoading = false,
                        sessions = sessions.map(::toSummary),
                        selectedSession = selectedSession?.let { selected ->
                            sessions.firstOrNull { it.id == selected.id } ?: selected
                        },
                    )
                }
            }
            .onFailure { error ->
                l.warn("Failed to load graph sessions", error)
                setState {
                    copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Failed to load graph sessions",
                    )
                }
            }
    }

    private suspend fun openSession(id: String) {
        setState { copy(isOpeningSession = true, errorMessage = null) }
        val result = withContext(ioDispatchers) {
            runCatching { sessionRepository.loadById(id) }
        }

        result
            .onSuccess { session ->
                if (session == null) {
                    setState {
                        copy(
                            isOpeningSession = false,
                            selectedSession = null,
                            errorMessage = "Session not found",
                        )
                    }
                } else {
                    setState {
                        copy(
                            isOpeningSession = false,
                            selectedSession = session,
                        )
                    }
                }
            }
            .onFailure { error ->
                l.warn("Failed to open graph session {}", id, error)
                setState {
                    copy(
                        isOpeningSession = false,
                        selectedSession = null,
                        errorMessage = error.message ?: "Failed to open graph session",
                    )
                }
            }
    }

    private fun toSummary(session: GraphSession): GraphSessionSummaryUi =
        GraphSessionSummaryUi(
            id = session.id,
            startTime = session.startTime,
            endTime = session.endTime,
            initialInput = session.initialInput,
            stepsCount = session.steps.size,
            nodePathPreview = session.steps.joinToString(" -> ") { step ->
                step.nodeName
                    .substringAfter("Node ")
                    .substringBefore(";")
                    .trim()
            },
        )
}
