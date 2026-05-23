package ru.souz.ui.settings

import ru.souz.agent.memory.MemoryEvidenceRecord
import ru.souz.agent.memory.MemoryGraphSnapshot
import ru.souz.agent.memory.MemoryScope
import ru.souz.ui.VMEvent
import ru.souz.ui.VMSideEffect
import ru.souz.ui.VMState

data class MemoryInspectorState(
    val scope: MemoryScope,
    val isLoading: Boolean = true,
    val isWorking: Boolean = false,
    val overview: MemoryInspectorOverview = MemoryInspectorOverview(),
    val graphSnapshot: MemoryGraphSnapshot = MemoryGraphSnapshot(),
    val timeline: List<MemoryGraphSnapshot.TimelineEvent> = emptyList(),
    val evidence: List<MemoryEvidenceRecord> = emptyList(),
    val diagnostics: MemoryInspectorDiagnostics = MemoryInspectorDiagnostics(),
    val rejectedWrites: List<MemoryInspectorWriteAttempt> = emptyList(),
    val recentInjections: List<MemoryInspectorInjectionLog> = emptyList(),
    val capabilities: MemoryInspectorCapabilities = MemoryInspectorCapabilities(),
    val selectedFactId: String? = null,
    val selectedEvidenceIndex: Int = 0,
    val canCopySelectedEvidence: Boolean = false,
    val canOpenSelectedSourceRef: Boolean = false,
    val errorMessage: String? = null,
) : VMState

sealed interface MemoryInspectorEvent : VMEvent {
    object Refresh : MemoryInspectorEvent
    data class SelectFact(val factId: String) : MemoryInspectorEvent
    data class SelectEvidence(val index: Int) : MemoryInspectorEvent
    object ForgetSelectedFact : MemoryInspectorEvent
    object InvalidateSelectedFact : MemoryInspectorEvent
    object RebuildEmbeddings : MemoryInspectorEvent
    object RunConsolidation : MemoryInspectorEvent
    object CopySelectedEvidence : MemoryInspectorEvent
    object OpenSelectedSourceRef : MemoryInspectorEvent
    data class ToggleAutoWrite(val enabled: Boolean) : MemoryInspectorEvent
}

sealed interface MemoryInspectorEffect : VMSideEffect {
    data class CopyToClipboard(val text: String) : MemoryInspectorEffect
    data class ShowSnackbar(val message: String) : MemoryInspectorEffect
}
