package ru.souz.ui.memory

import ru.souz.memory.CreateMemoryFactInput
import ru.souz.memory.MemoryFact
import ru.souz.memory.MemoryFactDetails
import ru.souz.memory.MemoryFactFilter
import ru.souz.memory.MemoryFactKind
import ru.souz.memory.MemoryFactPatch
import ru.souz.memory.MemoryFactStatus
import ru.souz.memory.MemoryMaintenanceBlockReason
import ru.souz.memory.MemoryMaintenanceMode
import ru.souz.memory.MemoryMaintenancePreferences
import ru.souz.memory.MemoryMaintenanceStatus
import ru.souz.memory.MemoryMaintenanceWorkerState
import ru.souz.memory.MemoryOwnerId
import ru.souz.memory.MemoryScope
import ru.souz.memory.normalizeCanonicalKey
import ru.souz.llms.LLMModel
import java.time.Instant
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
    val maintenance: MemoryMaintenanceUiState = MemoryMaintenanceUiState(),
) : VMState

data class MemoryMaintenanceUiState(
    val preferences: MemoryMaintenancePreferences = MemoryMaintenancePreferences(),
    val pendingClusters: Int = 0,
    val blockedClusters: Int = 0,
    val workerState: MemoryMaintenanceWorkerState = MemoryMaintenanceWorkerState.IDLE,
    val blockedReason: MemoryMaintenanceBlockReason? = MemoryMaintenanceBlockReason.DREAMER_DISABLED,
    val lastAttemptedAt: Instant? = null,
    val lastCompletedAt: Instant? = null,
    val isRunningNow: Boolean = false,
    val lastErrorCode: String? = null,
    val availableModels: List<LLMModel> = emptyList(),
) {
    val mode: MemoryMaintenanceMode get() = preferences.mode
    val isEnabled: Boolean get() = mode != MemoryMaintenanceMode.OFF
    val canRunNow: Boolean get() = isEnabled && !isRunningNow && pendingClusters > 0 && blockedReason == null
    val selectedModel: LLMModel? get() = availableModels.firstOrNull { it.alias == preferences.modelAlias }
    val runOutcome: MemoryMaintenanceRunOutcome
        get() = when {
            isRunningNow -> MemoryMaintenanceRunOutcome.RUNNING
            lastErrorCode != null -> MemoryMaintenanceRunOutcome.ERROR
            lastAttemptedAt == null -> MemoryMaintenanceRunOutcome.IDLE
            lastCompletedAt?.isBefore(lastAttemptedAt) == false -> MemoryMaintenanceRunOutcome.COMPLETED
            pendingClusters > 0 -> MemoryMaintenanceRunOutcome.RETRY_SCHEDULED
            else -> MemoryMaintenanceRunOutcome.NO_CHANGES
        }
}

enum class MemoryMaintenanceRunOutcome {
    IDLE,
    RUNNING,
    ERROR,
    RETRY_SCHEDULED,
    COMPLETED,
    NO_CHANGES,
}

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
    val canonicalKey: String?,
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
    data class SelectDreamerMode(val mode: MemoryMaintenanceMode) : MemoryAction
    data class SelectDreamerModel(val modelAlias: String?) : MemoryAction
    data object RunDreamerNow : MemoryAction
    data object RefreshDreamerStatus : MemoryAction
}

sealed interface MemoryEffect : VMSideEffect {
    data class ShowError(val message: String) : MemoryEffect
}

fun MemoryFiltersUi.toDomainFilter(ownerId: MemoryOwnerId? = null): MemoryFactFilter =
    MemoryFactFilter(
        ownerId = ownerId,
        statuses = when (status) {
            MemoryStatusFilter.ACTIVE -> setOf(MemoryFactStatus.ACTIVE)
            MemoryStatusFilter.RETIRED -> setOf(MemoryFactStatus.RETIRED)
            MemoryStatusFilter.ALL -> setOf(
                MemoryFactStatus.ACTIVE,
                MemoryFactStatus.RETIRED,
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

fun MemoryEditorInput.toCreateInput(ownerId: MemoryOwnerId): CreateMemoryFactInput =
    CreateMemoryFactInput(
        ownerId = ownerId,
        scope = MemoryScope(scopeType.trim(), scopeId.trim()),
        kind = kind,
        title = title.trim(),
        body = body.trim(),
        canonicalKey = normalizeCanonicalKey(canonicalKey),
        pinned = pinned,
    )

fun MemoryEditorInput.toPatch(): MemoryFactPatch {
    val trimmedCanonicalKey = canonicalKey?.trim()
    return MemoryFactPatch(
        scope = MemoryScope(scopeType.trim(), scopeId.trim()),
        kind = kind,
        title = title.trim(),
        body = body.trim(),
        canonicalKey = normalizeCanonicalKey(trimmedCanonicalKey),
        clearCanonicalKey = trimmedCanonicalKey.isNullOrBlank(),
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
            canonicalKey = fact.canonicalKey,
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
            canonicalKey = null,
            pinned = false,
        ),
    )

fun List<MemoryFact>.sortedForUi(): List<MemoryFact> =
    sortedWith(compareByDescending<MemoryFact> { it.pinned }.thenByDescending { it.updatedAt })

fun MemoryMaintenanceStatus.toUiState(isRunningNow: Boolean = false): MemoryMaintenanceUiState =
    MemoryMaintenanceUiState(
        preferences = preferences,
        pendingClusters = pendingClusters,
        blockedClusters = blockedClusters,
        workerState = workerState,
        blockedReason = blockedReason,
        lastAttemptedAt = lastAttemptedAt,
        lastCompletedAt = lastCompletedAt,
        isRunningNow = isRunningNow,
        lastErrorCode = lastErrorCode,
        availableModels = availableModels,
    )

fun MemoryMaintenanceUiState.toPreferences(): MemoryMaintenancePreferences =
    preferences
