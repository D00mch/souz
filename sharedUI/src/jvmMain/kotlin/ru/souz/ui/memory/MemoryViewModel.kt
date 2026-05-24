package ru.souz.ui.memory

import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import ru.souz.memory.MemoryService
import ru.souz.ui.BaseViewModel

class MemoryViewModel(
    override val di: DI,
) : BaseViewModel<MemoryUiState, MemoryAction, MemoryEffect>(), DIAware {

    private val memoryService: MemoryService by di.instance()

    override fun initialState(): MemoryUiState = MemoryUiState()

    fun onAction(action: MemoryAction) = send(action)

    override suspend fun handleEvent(event: MemoryAction) {
        when (event) {
            MemoryAction.Load -> loadFacts()
            is MemoryAction.ChangeFilters -> changeFilters(event.filters)
            MemoryAction.OpenCreateDialog -> setState { copy(editor = newMemoryEditorState(), error = null) }
            is MemoryAction.OpenEditDialog -> openEditDialog(event.factId)
            is MemoryAction.SaveFact -> saveFact(event.input)
            is MemoryAction.OpenDetails -> loadDetails(event.factId)
            MemoryAction.CloseDetails -> closeDetails()
            is MemoryAction.SetPinned -> setPinned(event.factId, event.pinned)
            is MemoryAction.AskRetire -> askRetire(event.factId)
            is MemoryAction.AskDelete -> askDelete(event.factId)
            MemoryAction.ConfirmAction -> confirmAction()
            MemoryAction.CancelConfirmAction -> setState { copy(confirmAction = null) }
            MemoryAction.CloseDialog -> setState { copy(editor = null) }
            MemoryAction.ClearError -> setState { copy(error = null) }
        }
    }

    override suspend fun handleSideEffect(effect: MemoryEffect) = Unit

    private suspend fun loadFacts() {
        setState { copy(isLoading = true, error = null) }
        runCatching {
            memoryService.listFacts(currentState.filters.toDomainFilter())
        }.onSuccess { facts ->
            setState {
                copy(
                    facts = facts.sortedForUi().map { it.toUi() },
                    isLoading = false,
                )
            }
        }.onFailure { error ->
            fail(error, "Failed to load memory")
            setState { copy(isLoading = false) }
        }
    }

    private suspend fun changeFilters(filters: MemoryFiltersUi) {
        setState { copy(filters = filters) }
        loadFacts()
    }

    private suspend fun openEditDialog(factId: String) {
        setState { copy(error = null) }
        runCatching {
            memoryService.getFactDetails(factId)?.toEditorState()
                ?: error("Memory fact not found: $factId")
        }.onSuccess { editor ->
            setState { copy(editor = editor) }
        }.onFailure { error ->
            fail(error, "Failed to open memory fact")
        }
    }

    private suspend fun saveFact(input: MemoryEditorInput) {
        validate(input)?.let { message ->
            fail(IllegalArgumentException(message), message)
            return
        }

        setState { copy(isSaving = true, error = null) }
        runCatching {
            if (input.factId == null) {
                memoryService.createManualFact(input.toCreateInput())
            } else {
                memoryService.updateFact(input.factId, input.toPatch())
            }
        }.onSuccess { fact ->
            setState { copy(isSaving = false, editor = null) }
            loadFacts()
            refreshDetailsIfSelected(fact.id)
        }.onFailure { error ->
            fail(error, "Failed to save memory fact")
            setState { copy(isSaving = false) }
        }
    }

    private suspend fun loadDetails(factId: String) {
        setState {
            copy(
                detailsFactId = factId,
                isDetailsLoading = true,
                selectedFact = null,
                error = null,
            )
        }
        runCatching {
            memoryService.getFactDetails(factId)?.toUi()
                ?: error("Memory fact not found: $factId")
        }.onSuccess { details ->
            setState {
                copy(
                    selectedFact = details,
                    isDetailsLoading = false,
                )
            }
        }.onFailure { error ->
            fail(error, "Failed to load memory details")
            setState {
                copy(
                    detailsFactId = null,
                    selectedFact = null,
                    isDetailsLoading = false,
                )
            }
        }
    }

    private suspend fun closeDetails() {
        setState {
            copy(
                detailsFactId = null,
                selectedFact = null,
                isDetailsLoading = false,
            )
        }
    }

    private suspend fun setPinned(
        factId: String,
        pinned: Boolean,
    ) {
        runCatching {
            memoryService.updateFact(factId, patch = ru.souz.memory.MemoryFactPatch(pinned = pinned))
        }.onSuccess {
            loadFacts()
            refreshDetailsIfSelected(factId)
        }.onFailure { error ->
            fail(error, "Failed to update memory fact")
        }
    }

    private suspend fun askRetire(factId: String) {
        setState {
            copy(confirmAction = MemoryConfirmAction.Retire(factId, factTitleFor(factId)))
        }
    }

    private suspend fun askDelete(factId: String) {
        setState {
            copy(confirmAction = MemoryConfirmAction.Delete(factId, factTitleFor(factId)))
        }
    }

    private suspend fun confirmAction() {
        val action = currentState.confirmAction ?: return
        setState { copy(confirmAction = null, error = null) }

        runCatching {
            when (action) {
                is MemoryConfirmAction.Delete -> memoryService.deleteFact(action.factId)
                is MemoryConfirmAction.Retire -> memoryService.retireFact(action.factId)
            }
        }.onSuccess {
            if (currentState.detailsFactId == action.factId) {
                closeDetails()
            }
            loadFacts()
        }.onFailure { error ->
            fail(
                error,
                when (action) {
                    is MemoryConfirmAction.Delete -> "Failed to delete memory fact"
                    is MemoryConfirmAction.Retire -> "Failed to retire memory fact"
                },
            )
        }
    }

    private suspend fun refreshDetailsIfSelected(factId: String) {
        if (currentState.detailsFactId == factId) {
            loadDetails(factId)
        }
    }

    private fun factTitleFor(factId: String): String =
        currentState.facts.firstOrNull { it.id == factId }?.title ?: factId

    private suspend fun fail(
        error: Throwable,
        fallback: String,
    ) {
        val message = error.message?.takeIf(String::isNotBlank) ?: fallback
        setState { copy(error = message) }
        send(MemoryEffect.ShowError(message))
    }

    private fun validate(input: MemoryEditorInput): String? = when {
        input.title.isBlank() -> "Title is required"
        input.body.isBlank() -> "Body is required"
        input.scopeType.isBlank() -> "Scope type is required"
        input.scopeId.isBlank() -> "Scope id is required"
        else -> null
    }
}
