package ru.souz.ui.settings

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import ru.souz.agent.memory.MemoryScope
import ru.souz.agent.memory.MemoryScopeType
import ru.souz.llms.LocalUserId
import ru.souz.ui.BaseViewModel

class MemoryInspectorViewModel(
    override val di: DI,
) : BaseViewModel<MemoryInspectorState, MemoryInspectorEvent, MemoryInspectorEffect>(), DIAware {
    private val service: MemoryInspectorService by di.instance()

    init {
        viewModelScope.launch { refresh() }
    }

    override fun initialState(): MemoryInspectorState =
        MemoryInspectorState(scope = MemoryScope(MemoryScopeType.USER, LocalUserId.default()))

    override suspend fun handleEvent(event: MemoryInspectorEvent) {
        when (event) {
            MemoryInspectorEvent.Refresh -> refresh()
            is MemoryInspectorEvent.SelectFact -> selectFact(event.factId)
            is MemoryInspectorEvent.SelectEvidence -> {
                setState {
                    copy(
                        selectedEvidenceIndex = event.index.coerceAtLeast(0),
                    )
                }
                updateSelectedEvidenceCapabilities()
            }
            MemoryInspectorEvent.ForgetSelectedFact -> runFactAction { factId -> service.forgetFact(factId) }
            MemoryInspectorEvent.InvalidateSelectedFact -> runFactAction { factId -> service.invalidateFact(factId) }
            MemoryInspectorEvent.RebuildEmbeddings -> runMaintenanceAction {
                service.rebuildEmbeddings()
                true
            }
            MemoryInspectorEvent.RunConsolidation -> runMaintenanceAction {
                service.runConsolidation()
            }
            MemoryInspectorEvent.CopySelectedEvidence -> {
                val evidence = currentSelectedEvidence() ?: return
                val text = evidence.contentExcerpt ?: evidence.contentJson ?: evidence.sourceRef
                if (text.isNotBlank()) {
                    send(MemoryInspectorEffect.CopyToClipboard(text))
                }
            }
            MemoryInspectorEvent.OpenSelectedSourceRef -> {
                val sourceRef = currentSelectedEvidence()?.sourceRef ?: return
                if (!service.canOpenSourceRef(sourceRef)) return
                runMaintenanceAction {
                    service.openSourceRef(sourceRef)
                }
            }
            is MemoryInspectorEvent.ToggleAutoWrite -> {
                if (!currentState.capabilities.autoWriteSupported) return
                runMaintenanceAction {
                    service.toggleAutoWrite(event.enabled)
                }
                setState {
                    copy(
                        capabilities = capabilities.copy(autoWriteEnabled = event.enabled),
                    )
                }
            }
        }
    }

    override suspend fun handleSideEffect(effect: MemoryInspectorEffect) = Unit

    private suspend fun refresh() {
        setState { copy(isLoading = true, errorMessage = null) }
        try {
            val scope = service.defaultScope()
            val overview = service.loadOverview(scope)
            val graphSnapshot = service.loadGraphSnapshot(scope)
            val timeline = service.loadTimeline(scope)
            val diagnostics = service.loadDiagnostics(scope)
            val rejectedWrites = service.loadRejectedWrites(scope)
            val recentInjections = service.loadRecentInjections(scope)
            val capabilities = service.capabilities()
            val selectedFactId = currentState.selectedFactId
                ?.takeIf { existing -> graphSnapshot.facts.any { it.id == existing } }
                ?: graphSnapshot.facts.firstOrNull()?.id
            val evidence = selectedFactId?.let { service.loadEvidence(scope, it) }.orEmpty()
            val selectedEvidence = evidence.firstOrNull()
            setState {
                copy(
                    isLoading = false,
                    scope = scope,
                    overview = overview,
                    graphSnapshot = graphSnapshot,
                    timeline = timeline,
                    diagnostics = diagnostics,
                    rejectedWrites = rejectedWrites,
                    recentInjections = recentInjections,
                    selectedFactId = selectedFactId,
                    selectedEvidenceIndex = 0,
                    evidence = evidence,
                    capabilities = capabilities,
                    canCopySelectedEvidence = selectedEvidence.hasCopyableContent(),
                    canOpenSelectedSourceRef = service.canOpenSourceRef(selectedEvidence?.sourceRef),
                    errorMessage = null,
                )
            }
        } catch (error: Throwable) {
            setState {
                copy(
                    isLoading = false,
                    errorMessage = error.message ?: "Failed to load memory inspector.",
                )
            }
        }
    }

    private suspend fun selectFact(factId: String) {
        val evidence = service.loadEvidence(currentState.scope, factId)
        setState {
            copy(
                selectedFactId = factId,
                selectedEvidenceIndex = 0,
                evidence = evidence,
                canCopySelectedEvidence = evidence.firstOrNull().hasCopyableContent(),
                canOpenSelectedSourceRef = service.canOpenSourceRef(evidence.firstOrNull()?.sourceRef),
            )
        }
    }

    private suspend fun runFactAction(action: suspend (String) -> Boolean) {
        val factId = currentState.selectedFactId ?: return
        runMaintenanceAction {
            action(factId)
        }
        refresh()
    }

    private suspend fun runMaintenanceAction(action: suspend () -> Boolean) {
        setState { copy(isWorking = true) }
        runCatching { action() }
        setState { copy(isWorking = false) }
    }

    private fun currentSelectedEvidence() =
        currentState.evidence.getOrNull(currentState.selectedEvidenceIndex)

    private suspend fun updateSelectedEvidenceCapabilities() {
        val evidence = currentSelectedEvidence()
        setState {
            copy(
                canCopySelectedEvidence = evidence.hasCopyableContent(),
                canOpenSelectedSourceRef = service.canOpenSourceRef(evidence?.sourceRef),
            )
        }
    }
}

private fun ru.souz.agent.memory.MemoryEvidenceRecord?.hasCopyableContent(): Boolean =
    !this?.contentExcerpt.isNullOrBlank() || !this?.contentJson.isNullOrBlank() || !this?.sourceRef.isNullOrBlank()
