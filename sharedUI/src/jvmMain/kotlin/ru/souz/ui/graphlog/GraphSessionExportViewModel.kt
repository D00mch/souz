package ru.souz.ui.graphlog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import ru.souz.agent.session.GraphSession
import java.nio.file.Path

internal data class GraphSessionExportState(
    val isExporting: Boolean = false,
    val savedPath: Path? = null,
    val failed: Boolean = false,
)

internal class GraphSessionExportViewModel(
    private val exportSession: suspend (GraphSession) -> Path?,
) : ViewModel() {
    private val state = MutableStateFlow(GraphSessionExportState())
    val uiState = state.asStateFlow()

    fun export(session: GraphSession): Job? {
        if (state.value.isExporting) return null
        state.value = GraphSessionExportState(isExporting = true)
        return viewModelScope.launch {
            try {
                state.value = GraphSessionExportState(savedPath = exportSession(session))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LoggerFactory.getLogger(GraphSessionExportViewModel::class.java)
                    .warn("Failed to export graph session ${session.id}", e)
                state.value = GraphSessionExportState(failed = true)
            } finally {
                state.value = state.value.copy(isExporting = false)
            }
        }
    }
}
