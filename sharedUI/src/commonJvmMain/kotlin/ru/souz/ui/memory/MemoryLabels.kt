package ru.souz.ui.memory

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import ru.souz.memory.MemoryEvidenceDetail
import ru.souz.memory.MemoryFactKind
import ru.souz.memory.MemoryFactStatus
import ru.souz.memory.MemoryScope
import souz.sharedui.generated.resources.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

internal const val DEFAULT_SCOPE_TYPE = "global"
internal const val DEFAULT_SCOPE_ID = "global"

@Composable
internal fun memoryScopeLabel(
    scopeType: String,
    scopeId: String,
): String {
    val cleanType = scopeType.trim()
    val cleanId = scopeId.trim()
    return when {
        cleanType.isBlank() && cleanId.isBlank() -> ""
        isGlobalScope(cleanType, cleanId) -> stringResource(Res.string.memory_scope_global)
        cleanType.equals("chat", ignoreCase = true) -> stringResource(Res.string.memory_scope_chat_format).format(cleanId)
        else -> "$cleanType:$cleanId"
    }
}

internal fun isGlobalScope(
    scopeType: String,
    scopeId: String,
): Boolean = scopeType.trim().equals(DEFAULT_SCOPE_TYPE, ignoreCase = true) &&
    scopeId.trim().equals(DEFAULT_SCOPE_ID, ignoreCase = true)

@Composable
internal fun MemoryScope.memoryLabel(): String = memoryScopeLabel(type, id)

@Composable
internal fun MemoryStatusFilter.label(): String = when (this) {
    MemoryStatusFilter.ACTIVE -> stringResource(Res.string.memory_filter_status_active)
    MemoryStatusFilter.RETIRED -> stringResource(Res.string.memory_filter_status_retired)
    MemoryStatusFilter.ALL -> stringResource(Res.string.memory_filter_status_all)
}

@Composable
internal fun MemoryFactStatus.label(): String = when (this) {
    MemoryFactStatus.ACTIVE -> stringResource(Res.string.memory_filter_status_active)
    MemoryFactStatus.RETIRED -> stringResource(Res.string.memory_filter_status_retired)
}

@Composable
internal fun MemoryFactKind.label(): String = when (this) {
    MemoryFactKind.SEMANTIC -> stringResource(Res.string.memory_kind_semantic)
    MemoryFactKind.PREFERENCE -> stringResource(Res.string.memory_kind_preference)
    MemoryFactKind.PROCEDURE -> stringResource(Res.string.memory_kind_procedure)
    MemoryFactKind.PROJECT_RULE -> stringResource(Res.string.memory_kind_project_rule)
    MemoryFactKind.EPISODE_NOTE -> stringResource(Res.string.memory_kind_episode_note)
    MemoryFactKind.PROJECT_DECISION -> stringResource(Res.string.memory_kind_project_decision)
}

@Composable
internal fun createdByLabel(createdBy: String): String = when (createdBy.lowercase(Locale.getDefault())) {
    "user" -> stringResource(Res.string.memory_created_by_manual)
    "writer" -> stringResource(Res.string.memory_created_by_auto)
    "system" -> stringResource(Res.string.memory_created_by_system)
    else -> createdBy.replaceFirstChar(Char::titlecase)
}

internal fun Float.confidenceLabel(): String = "${(this * 100f).roundToInt()}%"

internal fun Instant.memoryLabel(): String = MEMORY_TIME_FORMATTER.format(this)

internal fun Instant.shortMemoryLabel(): String = MEMORY_DATE_FORMATTER.format(this)

internal fun Instant.maintenanceLabel(): String = MEMORY_MAINTENANCE_TIME_FORMATTER.format(this)

internal fun String.preview(maxLength: Int = 180): String =
    trim().let { text ->
        if (text.length <= maxLength) text else text.take(maxLength).trimEnd() + "..."
    }

internal fun MemoryEvidenceDetail.displayText(): String =
    evidence.evidenceText
        ?.trim()
        .takeUnless { it.isNullOrBlank() }
        ?: sourceEvent.text.trim()

internal fun MemoryUiState.factTitle(factId: String): String =
    selectedFact?.fact?.takeIf { it.id == factId }?.title
        ?: facts.firstOrNull { it.id == factId }?.title
        ?: factId

private val MEMORY_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
        .withLocale(Locale.getDefault())
        .withZone(ZoneId.systemDefault())

private val MEMORY_DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy")
        .withLocale(Locale.getDefault())
        .withZone(ZoneId.systemDefault())

private val MEMORY_MAINTENANCE_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM HH:mm")
        .withLocale(Locale.getDefault())
        .withZone(ZoneId.systemDefault())
