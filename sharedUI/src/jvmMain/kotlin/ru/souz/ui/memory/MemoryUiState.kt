package ru.souz.ui.memory

import ru.souz.memory.CreateMemoryFactInput
import ru.souz.memory.MemoryFact
import ru.souz.memory.MemoryFactDetails
import ru.souz.memory.MemoryFactFilter
import ru.souz.memory.MemoryFactKind
import ru.souz.memory.MemoryFactPatch
import ru.souz.memory.MemoryFactStatus
import ru.souz.memory.MemoryScope
import ru.souz.ui.VMEvent
import ru.souz.ui.VMSideEffect
import ru.souz.ui.VMState

data class MemoryUiState(
    val facts: List<MemoryFact> = emptyList(),
    val selectedFact: MemoryFactDetails? = null,
    val detailsFactId: String? = null,
    val filters: MemoryFiltersUi = MemoryFiltersUi(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val isDetailsLoading: Boolean = false,
    val error: String? = null,
    val editor: MemoryEditorState? = null,
    val confirm: PendingMemoryConfirm? = null,
) : VMState

data class MemoryFiltersUi(
    val status: MemoryStatusFilter = MemoryStatusFilter.ACTIVE,
    val kind: MemoryFactKind? = null,
    val scopeType: String = "",
    val scopeId: String = "",
    val query: String = "",
)

enum class MemoryStatusFilter {
    ACTIVE,
    RETIRED,
    DELETED,
    ALL,
}

data class MemoryEditorState(
    val mode: MemoryEditorMode,
    val input: MemoryEditorInput,
)

enum class MemoryEditorMode {
    CREATE,
    EDIT,
}

data class MemoryEditorInput(
    val factId: String?,
    val title: String,
    val body: String,
    val kind: MemoryFactKind,
    val scopeType: String,
    val scopeId: String,
    val slotKey: String?,
    val pinned: Boolean,
)

data class PendingMemoryConfirm(
    val kind: Kind,
    val factId: String,
) {
    enum class Kind {
        Retire,
        Delete,
    }
}

sealed interface MemoryAction : VMEvent {
    data object Load : MemoryAction
    data class ChangeFilters(val filters: MemoryFiltersUi) : MemoryAction
    data object OpenCreateDialog : MemoryAction
    data class OpenEditDialog(val factId: String) : MemoryAction
    data class SaveFact(val input: MemoryEditorInput) : MemoryAction
    data class OpenDetails(val factId: String) : MemoryAction
    data object CloseDetails : MemoryAction
    data class SetPinned(val factId: String, val pinned: Boolean) : MemoryAction
    data class AskRetire(val factId: String) : MemoryAction
    data class AskDelete(val factId: String) : MemoryAction
    data object ConfirmAction : MemoryAction
    data object CancelConfirmAction : MemoryAction
    data object CloseDialog : MemoryAction
    data object ClearError : MemoryAction
}

sealed interface MemoryEffect : VMSideEffect {
    data class ShowError(val message: String) : MemoryEffect
}

fun MemoryFiltersUi.toDomainFilter(): MemoryFactFilter =
    MemoryFactFilter(
        statuses = when (status) {
            MemoryStatusFilter.ACTIVE -> setOf(MemoryFactStatus.ACTIVE)
            MemoryStatusFilter.RETIRED -> setOf(MemoryFactStatus.RETIRED)
            MemoryStatusFilter.DELETED -> setOf(MemoryFactStatus.DELETED)
            MemoryStatusFilter.ALL -> setOf(
                MemoryFactStatus.ACTIVE,
                MemoryFactStatus.RETIRED,
                MemoryFactStatus.DELETED,
            )
        },
        kinds = kind?.let(::setOf) ?: emptySet(),
        scope = if (scopeType.isNotBlank() && scopeId.isNotBlank()) {
            MemoryScope(scopeType.trim(), scopeId.trim())
        } else {
            null
        },
        query = query.trim().takeIf(String::isNotBlank),
    )

fun MemoryEditorInput.toCreateInput(): CreateMemoryFactInput =
    CreateMemoryFactInput(
        scope = MemoryScope(scopeType.trim(), scopeId.trim()),
        kind = kind,
        title = title.trim(),
        body = body.trim(),
        slotKey = slotKey?.trim()?.ifBlank { null },
        pinned = pinned,
    )

fun MemoryEditorInput.toPatch(): MemoryFactPatch {
    val trimmedSlotKey = slotKey?.trim()
    return MemoryFactPatch(
        scope = MemoryScope(scopeType.trim(), scopeId.trim()),
        kind = kind,
        title = title.trim(),
        body = body.trim(),
        slotKey = trimmedSlotKey?.ifBlank { null },
        clearSlotKey = trimmedSlotKey.isNullOrBlank(),
        pinned = pinned,
    )
}

fun MemoryFactDetails.toEditorState(): MemoryEditorState =
    MemoryEditorState(
        mode = MemoryEditorMode.EDIT,
        input = MemoryEditorInput(
            factId = fact.id,
            title = fact.title,
            body = fact.body,
            kind = fact.kind,
            scopeType = fact.scope.type,
            scopeId = fact.scope.id,
            slotKey = fact.slotKey,
            pinned = fact.pinned,
        ),
    )

fun newMemoryEditorState(): MemoryEditorState =
    MemoryEditorState(
        mode = MemoryEditorMode.CREATE,
        input = MemoryEditorInput(
            factId = null,
            title = "",
            body = "",
            kind = MemoryFactKind.SEMANTIC,
            scopeType = "global",
            scopeId = "global",
            slotKey = null,
            pinned = false,
        ),
    )

fun List<MemoryFact>.sortedForUi(): List<MemoryFact> =
    sortedWith(compareByDescending<MemoryFact> { it.pinned }.thenByDescending { it.updatedAt })
