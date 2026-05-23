package ru.souz.ui.settings

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.awt.Desktop
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import org.slf4j.LoggerFactory
import ru.souz.agent.memory.MemoryCandidate
import ru.souz.agent.memory.MemoryEvidenceRecord
import ru.souz.agent.memory.MemoryFactStatus
import ru.souz.agent.memory.MemoryGraphSnapshot
import ru.souz.agent.memory.MemoryMaintenanceService
import ru.souz.agent.memory.MemoryScope
import ru.souz.agent.memory.MemoryScopeType
import ru.souz.db.SettingsProvider
import ru.souz.llms.LocalUserId
import ru.souz.memory.MemoryInjectionLogRecord
import ru.souz.memory.MemoryWriteAttemptRecord
import ru.souz.memory.SqliteMemoryStore

data class MemoryInspectorOverview(
    val activeFactCount: Int = 0,
    val activeProfileDocCount: Int = 0,
    val activeFactDocCount: Int = 0,
    val activeEpisodeDocCount: Int = 0,
)

data class MemoryInspectorCapabilities(
    val autoWriteSupported: Boolean = false,
    val autoWriteEnabled: Boolean = false,
    val consolidationSupported: Boolean = false,
)

data class MemoryInspectorCandidateSummary(
    val label: String,
    val slotKey: String? = null,
    val rejectionReason: String? = null,
)

data class MemoryInspectorWriteAttempt(
    val turnRef: String? = null,
    val triggerType: String = "",
    val inputExcerpt: String? = null,
    val acceptedCount: Int = 0,
    val rejectedCount: Int = 0,
    val acceptedCandidates: List<MemoryInspectorCandidateSummary> = emptyList(),
    val rejectedCandidates: List<MemoryInspectorCandidateSummary> = emptyList(),
    val rejectionReasons: List<String> = emptyList(),
    val rawExtractionOutput: String? = null,
    val rawExtractionKind: String? = null,
    val emptyReason: String? = null,
    val createdAtIso: String = "",
)

data class MemoryInspectorInjectionLog(
    val turnRef: String? = null,
    val queryExcerpt: String? = null,
    val renderedPacket: String = "",
    val estimatedTokens: Int = 0,
    val selectedRecordCount: Int = 0,
    val createdAtIso: String = "",
)

data class MemoryInspectorDiagnostics(
    val automaticMemoryEnabled: Boolean = true,
    val lastWriteAttempt: MemoryInspectorWriteAttempt? = null,
    val lastInjection: MemoryInspectorInjectionLog? = null,
)

interface MemoryInspectorService {
    suspend fun defaultScope(): MemoryScope
    fun capabilities(): MemoryInspectorCapabilities
    suspend fun loadOverview(scope: MemoryScope): MemoryInspectorOverview
    suspend fun loadGraphSnapshot(scope: MemoryScope): MemoryGraphSnapshot
    suspend fun loadTimeline(scope: MemoryScope): List<MemoryGraphSnapshot.TimelineEvent>
    suspend fun loadEvidence(scope: MemoryScope, factId: String): List<MemoryEvidenceRecord>
    suspend fun loadDiagnostics(scope: MemoryScope): MemoryInspectorDiagnostics
    suspend fun loadRejectedWrites(scope: MemoryScope, limit: Int = 10): List<MemoryInspectorWriteAttempt>
    suspend fun loadRecentInjections(scope: MemoryScope, limit: Int = 10): List<MemoryInspectorInjectionLog>
    suspend fun forgetFact(factId: String): Boolean
    suspend fun invalidateFact(factId: String): Boolean
    suspend fun rebuildEmbeddings()
    suspend fun runConsolidation(): Boolean
    suspend fun toggleAutoWrite(enabled: Boolean): Boolean
    fun canOpenSourceRef(sourceRef: String?): Boolean
    suspend fun openSourceRef(sourceRef: String?): Boolean
}

class DefaultMemoryInspectorService(
    private val store: SqliteMemoryStore?,
    private val maintenanceService: MemoryMaintenanceService,
    private val settingsProvider: SettingsProvider,
) : MemoryInspectorService {
    private val logger = LoggerFactory.getLogger(DefaultMemoryInspectorService::class.java)
    private val mapper = jacksonObjectMapper().findAndRegisterModules()

    override suspend fun defaultScope(): MemoryScope =
        store?.latestActivityScope() ?: MemoryScope(MemoryScopeType.USER, LocalUserId.default())

    override fun capabilities(): MemoryInspectorCapabilities = MemoryInspectorCapabilities()

    override suspend fun loadOverview(scope: MemoryScope): MemoryInspectorOverview {
        val activeFacts = store?.listFacts(scope = scope, statuses = setOf(MemoryFactStatus.ACTIVE)).orEmpty()
        val docs = store?.listEmbeddingDocs(
            scopes = listOf(scope),
            fingerprint = settingsProvider.embeddingsModel.name,
        ).orEmpty()
        return MemoryInspectorOverview(
            activeFactCount = activeFacts.size,
            activeProfileDocCount = docs.count { it.docType.name == "PROFILE" },
            activeFactDocCount = docs.count { it.docType.name == "FACT" },
            activeEpisodeDocCount = docs.count { it.docType.name == "EPISODE" },
        )
    }

    override suspend fun loadGraphSnapshot(scope: MemoryScope): MemoryGraphSnapshot =
        store?.graphSnapshot(scope) ?: MemoryGraphSnapshot()

    override suspend fun loadTimeline(scope: MemoryScope): List<MemoryGraphSnapshot.TimelineEvent> =
        loadGraphSnapshot(scope).timelineEvents

    override suspend fun loadEvidence(
        scope: MemoryScope,
        factId: String,
    ): List<MemoryEvidenceRecord> =
        loadGraphSnapshot(scope).evidenceIndex[factId].orEmpty()

    override suspend fun loadDiagnostics(scope: MemoryScope): MemoryInspectorDiagnostics {
        val lastWriteAttempt = store?.recentWriteAttempts(scope = scope, limit = 1)?.firstOrNull()?.let(::toWriteAttempt)
        val lastInjection = store?.recentInjectionLogs(scope = scope, limit = 1)?.firstOrNull()?.let(::toInjectionLog)
        return MemoryInspectorDiagnostics(
            automaticMemoryEnabled = settingsProvider.memoryEnabled,
            lastWriteAttempt = lastWriteAttempt,
            lastInjection = lastInjection,
        )
    }

    override suspend fun loadRejectedWrites(
        scope: MemoryScope,
        limit: Int,
    ): List<MemoryInspectorWriteAttempt> =
        store?.recentWriteAttempts(scope = scope, limit = limit)
            .orEmpty()
            .map(::toWriteAttempt)
            .filter {
                it.rejectedCount > 0 ||
                    it.rejectedCandidates.isNotEmpty() ||
                    it.rejectionReasons.isNotEmpty() ||
                    !it.emptyReason.isNullOrBlank() ||
                    !it.rawExtractionOutput.isNullOrBlank()
            }

    override suspend fun loadRecentInjections(
        scope: MemoryScope,
        limit: Int,
    ): List<MemoryInspectorInjectionLog> =
        store?.recentInjectionLogs(scope = scope, limit = limit).orEmpty().map(::toInjectionLog)

    override suspend fun forgetFact(factId: String): Boolean =
        maintenanceService.forgetFact(factId = factId)

    override suspend fun invalidateFact(factId: String): Boolean =
        maintenanceService.invalidateFact(factId = factId)

    override suspend fun rebuildEmbeddings() {
        maintenanceService.rebuildProjection()
    }

    override suspend fun runConsolidation(): Boolean = false

    override suspend fun toggleAutoWrite(enabled: Boolean): Boolean = false

    override fun canOpenSourceRef(sourceRef: String?): Boolean =
        resolveSourceRef(sourceRef) != null

    override suspend fun openSourceRef(sourceRef: String?): Boolean {
        val target = resolveSourceRef(sourceRef) ?: return false
        return runCatching {
            if (!Desktop.isDesktopSupported()) return false
            val desktop = Desktop.getDesktop()
            when (target) {
                is SourceTarget.Browse -> {
                    if (!desktop.isSupported(Desktop.Action.BROWSE)) return false
                    desktop.browse(target.uri)
                }
                is SourceTarget.OpenFile -> {
                    if (!desktop.isSupported(Desktop.Action.OPEN)) return false
                    desktop.open(target.path.toFile())
                }
            }
            true
        }.onFailure { error ->
            logger.warn("Failed to open memory source ref {}", sourceRef, error)
        }.getOrDefault(false)
    }

    private fun toWriteAttempt(record: MemoryWriteAttemptRecord): MemoryInspectorWriteAttempt {
        val rejectionReasons = parseRejectionReasons(record.rejectionReasonsJson)
        val storedEnvelope = parseStoredEnvelope(record.candidatesJson)
        val audits = parseCandidateAudits(record.candidatesJson, rejectionReasons)
        return MemoryInspectorWriteAttempt(
            turnRef = record.turnRef,
            triggerType = record.triggerType.name,
            inputExcerpt = record.inputExcerpt,
            acceptedCount = record.acceptedCount,
            rejectedCount = record.rejectedCount,
            acceptedCandidates = audits
                .filter { it.accepted }
                .map { it.toSummary() },
            rejectedCandidates = audits
                .filterNot { it.accepted }
                .map { it.toSummary() },
            rejectionReasons = rejectionReasons,
            rawExtractionOutput = storedEnvelope?.rawOutput,
            rawExtractionKind = storedEnvelope?.rawOutputKind,
            emptyReason = storedEnvelope?.emptyReason,
            createdAtIso = record.createdAt.toString(),
        )
    }

    private fun toInjectionLog(record: MemoryInjectionLogRecord): MemoryInspectorInjectionLog =
        MemoryInspectorInjectionLog(
            turnRef = record.turnRef,
            queryExcerpt = record.queryExcerpt,
            renderedPacket = record.renderedPacket,
            estimatedTokens = record.estimatedTokens,
            selectedRecordCount = parseSelectedRecordIds(record.selectedRecordIdsJson).size,
            createdAtIso = record.createdAt.toString(),
        )

    private fun parseCandidateAudits(
        json: String,
        rejectionReasons: List<String>,
    ): List<StoredCandidateAudit> {
        val storedEnvelope = parseStoredEnvelope(json)
        if (storedEnvelope != null) return storedEnvelope.audits
        val modern = runCatching { mapper.readValue<List<StoredCandidateAudit>>(json) }.getOrNull()
        if (modern != null) return modern
        val legacy = runCatching { mapper.readValue<List<MemoryCandidate>>(json) }.getOrDefault(emptyList())
        return legacy.mapIndexed { index, candidate ->
            StoredCandidateAudit(
                candidate = candidate,
                accepted = index < legacy.size - rejectionReasons.size,
                rejectionReason = rejectionReasons.getOrNull(index - (legacy.size - rejectionReasons.size)),
            )
        }
    }

    private fun parseStoredEnvelope(json: String): StoredExtractionAuditEnvelope? =
        runCatching { mapper.readValue<StoredExtractionAuditEnvelope>(json) }.getOrNull()

    private fun parseRejectionReasons(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching { mapper.readValue<List<String>>(json) }.getOrDefault(emptyList())
    }

    private fun parseSelectedRecordIds(json: String): List<String> =
        runCatching { mapper.readValue<List<String>>(json) }.getOrDefault(emptyList())

    private fun StoredCandidateAudit.toSummary(): MemoryInspectorCandidateSummary =
        MemoryInspectorCandidateSummary(
            label = buildString {
                append(candidate.subjectDisplayName.ifBlank { candidate.subjectCanonicalName })
                append(" ")
                append(candidate.predicate.replace('_', ' '))
                append(": ")
                append(
                    candidate.objectEntityDisplayName
                        ?: candidate.objectEntityCanonicalName
                        ?: candidate.objectValueText
                        ?: candidate.objectValueJson
                        ?: candidate.objectKind.name.lowercase()
                )
            },
            slotKey = candidate.slotKey,
            rejectionReason = rejectionReason,
        )

    private fun resolveSourceRef(sourceRef: String?): SourceTarget? {
        val normalized = sourceRef?.trim().takeIf { !it.isNullOrEmpty() } ?: return null
        if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
            return runCatching { SourceTarget.Browse(URI(normalized)) }.getOrNull()
        }
        if (normalized.startsWith("file:/")) {
            return runCatching {
                val path = Path.of(URI(normalized))
                if (Files.exists(path)) SourceTarget.OpenFile(path) else null
            }.getOrNull()
        }
        val path = runCatching { Path.of(normalized) }.getOrNull()
        return if (path != null && path.isAbsolute && Files.exists(path)) {
            SourceTarget.OpenFile(path)
        } else {
            null
        }
    }
}

private data class StoredCandidateAudit(
    val candidate: MemoryCandidate,
    val accepted: Boolean,
    val rejectionReason: String? = null,
)

private data class StoredExtractionAuditEnvelope(
    val audits: List<StoredCandidateAudit> = emptyList(),
    val rawOutput: String? = null,
    val rawOutputKind: String? = null,
    val emptyReason: String? = null,
)

private sealed interface SourceTarget {
    data class Browse(val uri: URI) : SourceTarget
    data class OpenFile(val path: Path) : SourceTarget
}
