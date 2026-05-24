package ru.souz.ui.memory

import ru.souz.memory.CreateMemoryFactInput
import ru.souz.memory.MemoryEvidenceDetail
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

data class MemoryUiState(
    val facts: List<MemoryFactUi> = emptyList(),
    val selectedFact: MemoryFactDetailsUi? = null,
    val detailsFactId: String? = null,
    val filters: MemoryFiltersUi = MemoryFiltersUi(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val isDetailsLoading: Boolean = false,
    val error: String? = null,
    val editor: MemoryEditorState? = null,
    val confirmAction: MemoryConfirmAction? = null,
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

data class MemoryFactUi(
    val id: String,
    val title: String,
    val bodyPreview: String,
    val kind: String,
    val kindEnum: MemoryFactKind,
    val scopeLabel: String,
    val scopeType: String,
    val scopeId: String,
    val status: String,
    val statusEnum: MemoryFactStatus,
    val confidenceLabel: String,
    val pinned: Boolean,
    val createdBy: String,
    val createdByLabel: String,
    val updatedAtLabel: String,
    val updatedAtShortLabel: String,
)

data class MemoryFactDetailsUi(
    val id: String,
    val title: String,
    val body: String,
    val kind: String,
    val kindEnum: MemoryFactKind,
    val scopeLabel: String,
    val scopeType: String,
    val scopeId: String,
    val status: String,
    val statusEnum: MemoryFactStatus,
    val confidenceLabel: String,
    val pinned: Boolean,
    val slotKey: String?,
    val createdByLabel: String,
    val createdAtLabel: String,
    val updatedAtLabel: String,
    val supersedesFactId: String?,
    val evidence: List<MemoryEvidenceUi>,
)

data class MemoryEvidenceUi(
    val text: String,
    val sourceText: String,
    val sourceType: String,
    val sourceRef: String?,
    val createdAtLabel: String,
)

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

sealed interface MemoryConfirmAction {
    val factId: String
    val factTitle: String

    data class Retire(
        override val factId: String,
        override val factTitle: String,
    ) : MemoryConfirmAction

    data class Delete(
        override val factId: String,
        override val factTitle: String,
    ) : MemoryConfirmAction
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

fun MemoryFact.toUi(): MemoryFactUi =
    MemoryFactUi(
        id = id,
        title = title.trim(),
        bodyPreview = body.preview(),
        kind = kind.label(),
        kindEnum = kind,
        scopeLabel = scope.label(),
        scopeType = scope.type,
        scopeId = scope.id,
        status = status.label(),
        statusEnum = status,
        confidenceLabel = confidence.label(),
        pinned = pinned,
        createdBy = createdBy,
        createdByLabel = createdBy.label(),
        updatedAtLabel = updatedAt.label(),
        updatedAtShortLabel = updatedAt.shortLabel(),
    )

fun MemoryFactDetails.toUi(): MemoryFactDetailsUi =
    MemoryFactDetailsUi(
        id = fact.id,
        title = fact.title,
        body = fact.body,
        kind = fact.kind.label(),
        kindEnum = fact.kind,
        scopeLabel = fact.scope.label(),
        scopeType = fact.scope.type,
        scopeId = fact.scope.id,
        status = fact.status.label(),
        statusEnum = fact.status,
        confidenceLabel = fact.confidence.label(),
        pinned = fact.pinned,
        slotKey = fact.slotKey,
        createdByLabel = fact.createdBy.label(),
        createdAtLabel = fact.createdAt.label(),
        updatedAtLabel = fact.updatedAt.label(),
        supersedesFactId = fact.supersedesFactId,
        evidence = evidence.map(MemoryEvidenceDetail::toUi),
    )

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

private fun MemoryEvidenceDetail.toUi(): MemoryEvidenceUi =
    MemoryEvidenceUi(
        text = evidence.evidenceText
            ?.trim()
            .takeUnless { it.isNullOrBlank() }
            ?: sourceEvent.text.trim(),
        sourceText = sourceEvent.text.trim(),
        sourceType = sourceEvent.sourceType,
        sourceRef = sourceEvent.sourceRef,
        createdAtLabel = sourceEvent.createdAt.label(),
    )

private fun MemoryFactKind.label(): String = name.lowercase(Locale.getDefault())
    .split('_')
    .joinToString(" ") { token -> token.replaceFirstChar(Char::titlecase) }

private fun MemoryFactStatus.label(): String = name.lowercase(Locale.getDefault())
    .replaceFirstChar(Char::titlecase)

private fun MemoryScope.label(): String {
    val cleanType = type.trim()
    val cleanId = id.trim()
    return when {
        cleanType.equals("global", ignoreCase = true) && cleanId.equals("global", ignoreCase = true) -> "Global"
        cleanType.equals("chat", ignoreCase = true) -> "Chat: $cleanId"
        else -> "$cleanType:$cleanId"
    }
}

private fun Float.label(): String = "${(this * 100f).roundToInt()}%"

private fun String.label(): String = when (lowercase(Locale.getDefault())) {
    "user" -> "Manual"
    "writer" -> "Auto"
    "system" -> "System"
    else -> replaceFirstChar(Char::titlecase)
}

private fun Instant.label(): String = MEMORY_TIME_FORMATTER.format(this)

private fun Instant.shortLabel(): String = MEMORY_DATE_FORMATTER.format(this)

private fun String.preview(maxLength: Int = 180): String =
    trim().let { text ->
        if (text.length <= maxLength) {
            text
        } else {
            text.take(maxLength).trimEnd() + "…"
        }
    }

private val MEMORY_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
        .withLocale(Locale.getDefault())
        .withZone(ZoneId.systemDefault())

private val MEMORY_DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy")
        .withLocale(Locale.getDefault())
        .withZone(ZoneId.systemDefault())
