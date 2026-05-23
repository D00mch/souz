package ru.souz.memory

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.souz.agent.memory.MemoryEntityRecord
import ru.souz.agent.memory.MemoryEmbeddingDocRecord
import ru.souz.agent.memory.MemoryEpisodeRecord
import ru.souz.agent.memory.MemoryEvidenceRecord
import ru.souz.agent.memory.MemoryFactRecord
import ru.souz.agent.memory.MemoryFactStatus
import ru.souz.agent.memory.MemoryGraphQueryService
import ru.souz.agent.memory.MemoryGraphSnapshot
import ru.souz.agent.memory.MemoryMaintenanceService
import ru.souz.agent.memory.MemoryDocType
import ru.souz.agent.memory.MemoryObjectKind
import ru.souz.agent.memory.MemoryScope
import ru.souz.agent.memory.MemoryTriggerType
import ru.souz.paths.DefaultSouzPaths
import ru.souz.paths.SouzPaths

data class MemoryWriteAttemptRecord(
    val id: String,
    val scope: MemoryScope,
    val turnRef: String?,
    val triggerType: MemoryTriggerType,
    val inputExcerpt: String?,
    val candidatesJson: String,
    val acceptedCount: Int,
    val rejectedCount: Int,
    val rejectionReasonsJson: String?,
    val createdAt: Instant,
)

data class MemoryInjectionLogRecord(
    val id: String,
    val scope: MemoryScope,
    val turnRef: String?,
    val queryExcerpt: String?,
    val selectedRecordIdsJson: String,
    val renderedPacket: String,
    val estimatedTokens: Int,
    val createdAt: Instant,
)

class SqliteMemoryStore(
    private val paths: SouzPaths = DefaultSouzPaths(),
) : MemoryCanonicalStore, MemoryGraphQueryService, MemoryMaintenanceService {
    val databasePath: Path = paths.stateRoot.resolve("memory.db")

    private val mutex = Mutex()
    private val mapper = jacksonObjectMapper().findAndRegisterModules()

    init {
        migrate()
    }

    suspend fun resolveOrUpsertEntity(entity: MemoryEntityRecord): MemoryEntityRecord =
        resolveOrUpsertEntity(entity, emptyList())

    override suspend fun resolveOrUpsertEntity(
        entity: MemoryEntityRecord,
        aliases: List<String>,
    ): MemoryEntityRecord = write { connection ->
        val entityId = findEntityId(connection, entity.normalizedKey) ?: entity.id ?: newId()
        connection.prepareStatement(
            """
            insert into memory_entity(
                id,
                scope_type,
                scope_id,
                entity_type,
                canonical_name,
                display_name,
                normalized_key,
                status,
                created_at,
                updated_at
            )
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict(normalized_key) do update set
                scope_type = excluded.scope_type,
                scope_id = excluded.scope_id,
                entity_type = excluded.entity_type,
                canonical_name = excluded.canonical_name,
                display_name = excluded.display_name,
                status = excluded.status,
                updated_at = excluded.updated_at
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, entityId)
            statement.setString(2, entity.scope.type.name)
            statement.setString(3, entity.scope.id)
            statement.setString(4, entity.entityType)
            statement.setString(5, entity.canonicalName)
            statement.setString(6, entity.displayName)
            statement.setString(7, entity.normalizedKey)
            statement.setString(8, entity.status)
            statement.setInstantText(9, entity.createdAt)
            statement.setInstantText(10, entity.updatedAt)
            statement.executeUpdate()
        }
        val stored = selectEntityByNormalizedKey(connection, entity.normalizedKey)
            ?: error("Stored entity is missing for normalized key ${entity.normalizedKey}.")
        aliases
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .forEach { alias ->
                connection.prepareStatement(
                    """
                    insert or ignore into memory_entity_alias(
                        id,
                        entity_id,
                        alias,
                        normalized_alias,
                        source,
                        created_at
                    )
                    values (?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, newId())
                    statement.setString(2, stored.id)
                    statement.setString(3, alias)
                    statement.setString(4, alias.lowercase())
                    statement.setString(5, "upsert")
                    statement.setInstantText(6, stored.updatedAt)
                    statement.executeUpdate()
                }
            }
        stored
    }

    override suspend fun listEntitiesByIds(entityIds: Set<String>): List<MemoryEntityRecord> = read { connection ->
        connection.loadEntities(entityIds)
    }

    override suspend fun insertEvidence(evidence: MemoryEvidenceRecord): MemoryEvidenceRecord = write { connection ->
        val stored = evidence.copy(id = evidence.id ?: newId())
        connection.prepareStatement(
            """
            insert into memory_evidence(
                id,
                scope_type,
                scope_id,
                evidence_type,
                source_ref,
                source_hash,
                content_excerpt,
                content_json,
                created_at
            )
            values (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, stored.id)
            statement.setString(2, stored.scope.type.name)
            statement.setString(3, stored.scope.id)
            statement.setString(4, stored.evidenceType.name)
            statement.setString(5, stored.sourceRef)
            statement.setNullableString(6, stored.sourceHash)
            statement.setNullableString(7, stored.contentExcerpt)
            statement.setNullableString(8, stored.contentJson)
            statement.setInstantText(9, stored.createdAt)
            statement.executeUpdate()
        }
        stored
    }

    override suspend fun insertFact(
        fact: MemoryFactRecord,
        evidenceIds: List<String>,
    ): MemoryFactRecord = write { connection ->
        val stored = fact.copy(id = fact.id ?: newId())
        stored.slotKey?.let { slotKey ->
            connection.prepareStatement(
                """
                update memory_fact
                set status = ?,
                    invalidated_at = ?
                where slot_key = ?
                  and status = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, MemoryFactStatus.SUPERSEDED.name)
                statement.setInstantText(2, stored.validFrom)
                statement.setString(3, slotKey)
                statement.setString(4, MemoryFactStatus.ACTIVE.name)
                statement.executeUpdate()
            }
        }
        connection.prepareStatement(
            """
            insert into memory_fact(
                id,
                scope_type,
                scope_id,
                subject_entity_id,
                predicate,
                object_kind,
                object_entity_id,
                object_value_text,
                object_value_json,
                slot_key,
                confidence,
                status,
                reason_to_store,
                created_at,
                valid_from,
                invalidated_at,
                invalidated_by_fact_id,
                origin_episode_id,
                writer_version
            )
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, stored.id)
            statement.setString(2, stored.scope.type.name)
            statement.setString(3, stored.scope.id)
            statement.setString(4, stored.subjectEntityId)
            statement.setString(5, stored.predicate)
            statement.setString(6, stored.objectKind.name)
            statement.setNullableString(7, stored.objectEntityId)
            statement.setNullableString(8, stored.objectValueText)
            statement.setNullableString(9, stored.objectValueJson)
            statement.setNullableString(10, stored.slotKey)
            statement.setDouble(11, stored.confidence)
            statement.setString(12, stored.status.name)
            statement.setString(13, stored.reasonToStore)
            statement.setInstantText(14, stored.createdAt)
            statement.setInstantText(15, stored.validFrom)
            statement.setNullableInstantText(16, stored.invalidatedAt)
            statement.setNullableString(17, stored.invalidatedByFactId)
            statement.setNullableString(18, stored.originEpisodeId)
            statement.setNullableString(19, stored.writerVersion)
            statement.executeUpdate()
        }
        evidenceIds.distinct().forEach { evidenceId ->
            connection.prepareStatement(
                """
                insert into memory_fact_evidence(
                    id,
                    fact_id,
                    evidence_id,
                    support_type,
                    weight,
                    created_at
                )
                values (?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, newId())
                statement.setString(2, stored.id)
                statement.setString(3, evidenceId)
                statement.setString(4, "PRIMARY")
                statement.setDouble(5, 1.0)
                statement.setInstantText(6, stored.createdAt)
                statement.executeUpdate()
            }
        }
        stored.slotKey?.let { slotKey ->
            connection.prepareStatement(
                """
                update memory_fact
                set invalidated_by_fact_id = ?
                where slot_key = ?
                  and status = ?
                  and id <> ?
                  and invalidated_by_fact_id is null
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, stored.id)
                statement.setString(2, slotKey)
                statement.setString(3, MemoryFactStatus.SUPERSEDED.name)
                statement.setString(4, stored.id)
                statement.executeUpdate()
            }
        }
        stored
    }

    suspend fun listActiveFacts(scope: MemoryScope): List<MemoryFactRecord> = read { connection ->
        connection.prepareStatement(
            """
            select *
            from memory_fact
            where scope_type = ?
              and scope_id = ?
              and status = ?
            order by created_at desc
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, scope.type.name)
            statement.setString(2, scope.id)
            statement.setString(3, MemoryFactStatus.ACTIVE.name)
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(resultSet.toFact())
                    }
                }
            }
        }
    }

    override suspend fun listFacts(
        scope: MemoryScope?,
        statuses: Set<MemoryFactStatus>,
    ): List<MemoryFactRecord> = read { connection ->
        val statusValues = statuses.ifEmpty { setOf(MemoryFactStatus.ACTIVE) }.map { it.name }
        val placeholders = statusValues.joinToString(",") { "?" }
        val sql = buildString {
            append(
                """
                select *
                from memory_fact
                where status in ($placeholders)
                """.trimIndent()
            )
            if (scope != null) {
                append(" and scope_type = ? and scope_id = ?")
            }
            append(" order by created_at desc")
        }
        connection.prepareStatement(sql).use { statement ->
            var index = 1
            statusValues.forEach { status ->
                statement.setString(index++, status)
            }
            if (scope != null) {
                statement.setString(index++, scope.type.name)
                statement.setString(index, scope.id)
            }
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(resultSet.toFact())
                    }
                }
            }
        }
    }

    suspend fun listFacts(scope: MemoryScope? = null): List<MemoryFactRecord> =
        listFacts(scope = scope, statuses = setOf(MemoryFactStatus.ACTIVE))

    override suspend fun insertEpisode(episode: MemoryEpisodeRecord): MemoryEpisodeRecord = write { connection ->
        val stored = episode.copy(id = episode.id ?: newId())
        connection.prepareStatement(
            """
            insert into memory_episode(
                id,
                scope_type,
                scope_id,
                title,
                summary,
                status,
                started_at,
                ended_at,
                last_touched_at,
                next_action,
                importance
            )
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, stored.id)
            statement.setString(2, stored.scope.type.name)
            statement.setString(3, stored.scope.id)
            statement.setString(4, stored.title)
            statement.setString(5, stored.summary)
            statement.setString(6, stored.status)
            statement.setInstantText(7, stored.startedAt)
            statement.setNullableInstantText(8, stored.endedAt)
            statement.setInstantText(9, stored.lastTouchedAt)
            statement.setNullableString(10, stored.nextAction)
            val importance = stored.importance
            if (importance == null) {
                statement.setNull(11, Types.DOUBLE)
            } else {
                statement.setDouble(11, importance)
            }
            statement.executeUpdate()
        }
        stored
    }

    override suspend fun listEpisodes(
        scopes: List<MemoryScope>,
        statuses: Set<String>,
    ): List<MemoryEpisodeRecord> = read { connection ->
        val statusValues = statuses.ifEmpty { setOf("ACTIVE") }
        val statusPlaceholders = statusValues.joinToString(",") { "?" }
        val sql = buildString {
            append(
                """
                select *
                from memory_episode
                where status in ($statusPlaceholders)
                """.trimIndent()
            )
            if (scopes.isNotEmpty()) {
                append(
                    scopes.joinToString(
                        prefix = " and (",
                        postfix = ")",
                        separator = " or ",
                    ) { "(scope_type = ? and scope_id = ?)" }
                )
            }
            append(" order by last_touched_at desc")
        }
        connection.prepareStatement(sql).use { statement ->
            var index = 1
            statusValues.forEach { status ->
                statement.setString(index++, status)
            }
            scopes.forEach { scope ->
                statement.setString(index++, scope.type.name)
                statement.setString(index++, scope.id)
            }
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(resultSet.toEpisode())
                    }
                }
            }
        }
    }

    override suspend fun replaceEmbeddingDocs(docs: List<MemoryEmbeddingDocRecord>) = write { connection ->
        connection.createStatement().use { statement ->
            statement.executeUpdate("delete from memory_embedding_doc")
        }
        docs.forEach { doc ->
            val stored = doc.copy(id = doc.id ?: newId())
            connection.prepareStatement(
                """
                insert into memory_embedding_doc(
                    id,
                    doc_type,
                    source_record_type,
                    source_record_id,
                    scope_type,
                    scope_id,
                    text,
                    status,
                    embedding_model_fingerprint,
                    created_at,
                    updated_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, stored.id)
                statement.setString(2, stored.docType.name)
                statement.setString(3, stored.sourceRecordType)
                statement.setString(4, stored.sourceRecordId)
                statement.setString(5, stored.scope.type.name)
                statement.setString(6, stored.scope.id)
                statement.setString(7, stored.text)
                statement.setString(8, stored.status)
                statement.setNullableString(9, stored.embeddingModelFingerprint)
                statement.setInstantText(10, stored.createdAt)
                statement.setInstantText(11, stored.updatedAt)
                statement.executeUpdate()
            }
        }
    }

    override suspend fun listEmbeddingDocs(
        scopes: List<MemoryScope>,
        docTypes: Set<MemoryDocType>,
        fingerprint: String?,
    ): List<MemoryEmbeddingDocRecord> = read { connection ->
        val sql = buildString {
            append(
                """
                select *
                from memory_embedding_doc
                where status = 'ACTIVE'
                """.trimIndent()
            )
            if (fingerprint != null) {
                append(" and embedding_model_fingerprint = ?")
            }
            if (docTypes.isNotEmpty()) {
                append(docTypes.joinToString(prefix = " and doc_type in (", postfix = ")", separator = ",") { "?" })
            }
            if (scopes.isNotEmpty()) {
                append(
                    scopes.joinToString(
                        prefix = " and (",
                        postfix = ")",
                        separator = " or ",
                    ) { "(scope_type = ? and scope_id = ?)" }
                )
            }
            append(" order by updated_at desc")
        }
        connection.prepareStatement(sql).use { statement ->
            var index = 1
            if (fingerprint != null) {
                statement.setString(index++, fingerprint)
            }
            docTypes.forEach { docType ->
                statement.setString(index++, docType.name)
            }
            scopes.forEach { scope ->
                statement.setString(index++, scope.type.name)
                statement.setString(index++, scope.id)
            }
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(resultSet.toEmbeddingDoc())
                    }
                }
            }
        }
    }

    override suspend fun logWriteAttempt(
        scope: MemoryScope,
        turnRef: String?,
        triggerType: MemoryTriggerType,
        inputExcerpt: String?,
        candidatesJson: String,
        acceptedCount: Int,
        rejectedCount: Int,
        rejectionReasonsJson: String?,
        createdAt: Instant,
    ): String = write { connection ->
        val id = newId()
        connection.prepareStatement(
            """
            insert into memory_write_attempt(
                id,
                scope_type,
                scope_id,
                turn_ref,
                trigger_type,
                input_excerpt,
                candidates_json,
                accepted_count,
                rejected_count,
                rejection_reasons_json,
                created_at
            )
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, id)
            statement.setString(2, scope.type.name)
            statement.setString(3, scope.id)
            statement.setNullableString(4, turnRef)
            statement.setString(5, triggerType.name)
            statement.setNullableString(6, inputExcerpt)
            statement.setString(7, candidatesJson)
            statement.setInt(8, acceptedCount)
            statement.setInt(9, rejectedCount)
            statement.setNullableString(10, rejectionReasonsJson)
            statement.setInstantText(11, createdAt)
            statement.executeUpdate()
        }
        id
    }

    suspend fun logWriteAttempt(
        scope: MemoryScope,
        turnRef: String?,
        triggerType: MemoryTriggerType,
        inputExcerpt: String?,
        candidatesJson: String,
        acceptedCount: Int,
        rejectedCount: Int,
        rejectionReasonsJson: String?,
    ): String = logWriteAttempt(
        scope = scope,
        turnRef = turnRef,
        triggerType = triggerType,
        inputExcerpt = inputExcerpt,
        candidatesJson = candidatesJson,
        acceptedCount = acceptedCount,
        rejectedCount = rejectedCount,
        rejectionReasonsJson = rejectionReasonsJson,
        createdAt = Instant.now(),
    )

    override suspend fun logInjection(
        scope: MemoryScope,
        turnRef: String?,
        queryExcerpt: String?,
        selectedRecordIds: List<String>,
        renderedPacket: String,
        estimatedTokens: Int,
        createdAt: Instant,
    ): String = write { connection ->
        val id = newId()
        connection.prepareStatement(
            """
            insert into memory_injection_log(
                id,
                scope_type,
                scope_id,
                turn_ref,
                query_excerpt,
                selected_record_ids_json,
                rendered_packet,
                estimated_tokens,
                created_at
            )
            values (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, id)
            statement.setString(2, scope.type.name)
            statement.setString(3, scope.id)
            statement.setNullableString(4, turnRef)
            statement.setNullableString(5, queryExcerpt)
            statement.setString(6, mapper.writeValueAsString(selectedRecordIds))
            statement.setString(7, renderedPacket)
            statement.setInt(8, estimatedTokens)
            statement.setInstantText(9, createdAt)
            statement.executeUpdate()
        }
        id
    }

    suspend fun logInjection(
        scope: MemoryScope,
        turnRef: String?,
        queryExcerpt: String?,
        selectedRecordIds: List<String>,
        renderedPacket: String,
        estimatedTokens: Int,
    ): String = logInjection(
        scope = scope,
        turnRef = turnRef,
        queryExcerpt = queryExcerpt,
        selectedRecordIds = selectedRecordIds,
        renderedPacket = renderedPacket,
        estimatedTokens = estimatedTokens,
        createdAt = Instant.now(),
    )

    suspend fun recentWriteAttempts(
        scope: MemoryScope,
        limit: Int = 20,
    ): List<MemoryWriteAttemptRecord> = read { connection ->
        connection.loadWriteAttempts(scope = scope, limit = limit)
    }

    suspend fun recentInjectionLogs(
        scope: MemoryScope,
        limit: Int = 20,
    ): List<MemoryInjectionLogRecord> = read { connection ->
        connection.loadInjectionLogs(scope = scope, limit = limit)
    }

    suspend fun latestActivityScope(): MemoryScope? = read { connection ->
        connection.loadLatestActivityScope()
    }

    override suspend fun graphSnapshot(scope: MemoryScope): MemoryGraphSnapshot = read { connection ->
        val facts = connection.loadFacts(scope)
        val entityIds = facts.flatMap { listOfNotNull(it.subjectEntityId, it.objectEntityId) }.toSet()
        val entities = connection.loadEntities(entityIds)
        val evidenceIndex = connection.loadEvidenceIndex(facts.mapNotNull { it.id })
        val timelineEvents = connection.loadTimeline(scope)
        MemoryGraphSnapshot(
            entities = entities,
            facts = facts,
            edges = facts.mapNotNull { fact ->
                val factId = fact.id ?: return@mapNotNull null
                val objectEntityId = fact.objectEntityId ?: return@mapNotNull null
                if (fact.objectKind != MemoryObjectKind.ENTITY) {
                    null
                } else {
                    MemoryGraphSnapshot.Edge(
                        factId = factId,
                        subjectEntityId = fact.subjectEntityId,
                        predicate = fact.predicate,
                        objectEntityId = objectEntityId,
                    )
                }
            },
            attributes = facts.mapNotNull { fact ->
                val factId = fact.id ?: return@mapNotNull null
                if (fact.objectKind == MemoryObjectKind.ENTITY) {
                    null
                } else {
                    MemoryGraphSnapshot.Attribute(
                        factId = factId,
                        subjectEntityId = fact.subjectEntityId,
                        predicate = fact.predicate,
                        value = fact.objectValueText ?: fact.objectValueJson.orEmpty(),
                    )
                }
            },
            timelineEvents = timelineEvents,
            evidenceIndex = evidenceIndex,
            diagnostics = MemoryGraphSnapshot.Diagnostics(
                writeAttemptCount = connection.countByScope("memory_write_attempt", scope),
                injectionLogCount = connection.countByScope("memory_injection_log", scope),
            ),
            generatedAt = Instant.now(),
        )
    }

    override suspend fun forgetFact(factId: String, at: Instant): Boolean =
        updateFactStatus(factId = factId, status = MemoryFactStatus.FORGOTTEN, at = at)

    override suspend fun invalidateFact(factId: String, at: Instant): Boolean =
        updateFactStatus(factId = factId, status = MemoryFactStatus.INVALIDATED, at = at)

    override suspend fun rebuildProjection() = Unit

    private suspend fun updateFactStatus(
        factId: String,
        status: MemoryFactStatus,
        at: Instant,
    ): Boolean = write { connection ->
        connection.prepareStatement(
            """
            update memory_fact
            set status = ?,
                invalidated_at = ?
            where id = ?
              and status = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, status.name)
            statement.setInstantText(2, at)
            statement.setString(3, factId)
            statement.setString(4, MemoryFactStatus.ACTIVE.name)
            statement.executeUpdate() > 0
        }
    }

    private fun migrate() {
        Files.createDirectories(paths.stateRoot)
        openConnection().use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        create table if not exists memory_schema_migration(
                            version integer primary key,
                            name text not null,
                            applied_at text not null
                        )
                        """.trimIndent()
                    )
                }
                val applied = connection.prepareStatement(
                    "select version from memory_schema_migration"
                ).use { statement ->
                    statement.executeQuery().use { resultSet ->
                        buildSet {
                            while (resultSet.next()) {
                                add(resultSet.getInt("version"))
                            }
                        }
                    }
                }
                SQLITE_MIGRATIONS
                    .filterNot { applied.contains(it.version) }
                    .forEach { migration ->
                        migration.resourcePath?.let { resourcePath ->
                            executeStatements(connection, loadMigration(resourcePath))
                        }
                        applyMigrationCompatibilityFixes(connection, migration.version)
                        connection.prepareStatement(
                            """
                            insert into memory_schema_migration(version, name, applied_at)
                            values (?, ?, ?)
                            """.trimIndent()
                        ).use { statement ->
                            statement.setInt(1, migration.version)
                            statement.setString(2, migration.name)
                            statement.setInstantText(3, Instant.now())
                            statement.executeUpdate()
                        }
                    }
                connection.commit()
            } catch (t: Throwable) {
                runCatching { connection.rollback() }
                throw t
            } finally {
                connection.autoCommit = true
            }
        }
    }

    private fun applyMigrationCompatibilityFixes(
        connection: Connection,
        version: Int,
    ) {
        if (version == LEGACY_MEMORY_EVIDENCE_COMPAT_VERSION) {
            migrateLegacyMemoryEvidenceTable(connection)
        }
    }

    private fun migrateLegacyMemoryEvidenceTable(connection: Connection) {
        val columns = connection.tableColumns("memory_evidence")
        if (columns.isEmpty() || CURRENT_MEMORY_EVIDENCE_COLUMNS.all(columns::contains)) {
            return
        }
        if (!LEGACY_MEMORY_EVIDENCE_COLUMNS.all(columns::contains)) {
            return
        }

        connection.createStatement().use { statement ->
            statement.execute("alter table memory_evidence rename to memory_evidence_legacy_v0")
            statement.execute(
                """
                create table memory_evidence (
                    id text primary key,
                    scope_type text not null,
                    scope_id text not null,
                    evidence_type text not null,
                    source_ref text not null,
                    source_hash text,
                    content_excerpt text,
                    content_json text,
                    created_at text not null
                )
                """.trimIndent()
            )
            statement.execute(
                """
                insert into memory_evidence(
                    id,
                    scope_type,
                    scope_id,
                    evidence_type,
                    source_ref,
                    source_hash,
                    content_excerpt,
                    content_json,
                    created_at
                )
                select
                    cast(id as text),
                    'GLOBAL',
                    'legacy-memory-evidence',
                    case upper(coalesce(source_type, ''))
                        when 'USER_MESSAGE' then 'USER_MESSAGE'
                        when 'ASSISTANT_MESSAGE' then 'ASSISTANT_MESSAGE'
                        when 'TOOL_OUTPUT' then 'TOOL_OUTPUT'
                        when 'FILE_EXCERPT' then 'FILE_EXCERPT'
                        when 'WEB_EXCERPT' then 'WEB_EXCERPT'
                        when 'SYSTEM_METADATA' then 'SYSTEM_METADATA'
                        when 'EPISODE_SUMMARY' then 'EPISODE_SUMMARY'
                        else 'SYSTEM_METADATA'
                    end,
                    coalesce(source_ref, 'legacy-evidence:' || cast(id as text)),
                    null,
                    excerpt,
                    null,
                    case
                        when typeof(created_at) = 'integer'
                            then strftime('%Y-%m-%dT%H:%M:%fZ', created_at / 1000.0, 'unixepoch')
                        else cast(created_at as text)
                    end
                from memory_evidence_legacy_v0
                """.trimIndent()
            )
            statement.execute("drop table memory_evidence_legacy_v0")
        }
    }

    private fun executeStatements(connection: Connection, sql: String) {
        sql.split(";")
            .map(String::trim)
            .filter(String::isNotEmpty)
            .forEach { statementSql ->
                connection.createStatement().use { statement ->
                    statement.execute(statementSql)
                }
            }
    }

    private fun loadMigration(resourcePath: String): String =
        SqliteMemoryStore::class.java.classLoader.getResourceAsStream(resourcePath)?.bufferedReader()?.use { it.readText() }
            ?: error("Missing SQLite memory migration resource: $resourcePath")

    private fun Connection.tableColumns(tableName: String): Set<String> =
        prepareStatement("pragma table_info($tableName)").use { statement ->
            statement.executeQuery().use { resultSet ->
                buildSet {
                    while (resultSet.next()) {
                        add(resultSet.getString("name"))
                    }
                }
            }
        }

    private fun findEntityId(connection: Connection, normalizedKey: String): String? =
        connection.prepareStatement(
            "select id from memory_entity where normalized_key = ?"
        ).use { statement ->
            statement.setString(1, normalizedKey)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.getString("id") else null
            }
        }

    private fun selectEntityByNormalizedKey(connection: Connection, normalizedKey: String): MemoryEntityRecord? =
        connection.prepareStatement(
            "select * from memory_entity where normalized_key = ?"
        ).use { statement ->
            statement.setString(1, normalizedKey)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toEntity() else null
            }
        }

    private fun openConnection(): Connection =
        DriverManager.getConnection("jdbc:sqlite:$databasePath").also { connection ->
            connection.createStatement().use { statement ->
                statement.execute("pragma foreign_keys = on")
            }
        }

    private suspend fun <T> read(block: (Connection) -> T): T =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                openConnection().use(block)
            }
        }

    private suspend fun <T> write(block: (Connection) -> T): T =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                openConnection().use { connection ->
                    val previousAutoCommit = connection.autoCommit
                    connection.autoCommit = false
                    try {
                        val result = block(connection)
                        connection.commit()
                        result
                    } catch (t: Throwable) {
                        runCatching { connection.rollback() }
                        throw t
                    } finally {
                        connection.autoCommit = previousAutoCommit
                    }
                }
            }
        }

    private fun Connection.loadFacts(scope: MemoryScope): List<MemoryFactRecord> =
        prepareStatement(
            """
            select *
            from memory_fact
            where scope_type = ?
              and scope_id = ?
              and status = ?
            order by created_at desc
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, scope.type.name)
            statement.setString(2, scope.id)
            statement.setString(3, MemoryFactStatus.ACTIVE.name)
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(resultSet.toFact())
                    }
                }
            }
        }

    private fun Connection.loadEntities(entityIds: Set<String>): List<MemoryEntityRecord> {
        if (entityIds.isEmpty()) {
            return emptyList()
        }
        val placeholders = entityIds.joinToString(",") { "?" }
        return prepareStatement(
            "select * from memory_entity where id in ($placeholders)"
        ).use { statement ->
            entityIds.forEachIndexed { index, entityId ->
                statement.setString(index + 1, entityId)
            }
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(resultSet.toEntity())
                    }
                }
            }
        }
    }

    private fun Connection.loadEvidenceIndex(factIds: List<String>): Map<String, List<MemoryEvidenceRecord>> {
        if (factIds.isEmpty()) {
            return emptyMap()
        }
        val placeholders = factIds.joinToString(",") { "?" }
        return prepareStatement(
            """
            select fe.fact_id as fact_id, e.*
            from memory_fact_evidence fe
            join memory_evidence e on e.id = fe.evidence_id
            where fe.fact_id in ($placeholders)
            order by e.created_at asc
            """.trimIndent()
        ).use { statement ->
            factIds.forEachIndexed { index, factId ->
                statement.setString(index + 1, factId)
            }
            statement.executeQuery().use { resultSet ->
                val evidenceByFact = linkedMapOf<String, MutableList<MemoryEvidenceRecord>>()
                while (resultSet.next()) {
                    val factId = resultSet.getString("fact_id")
                    evidenceByFact.getOrPut(factId) { mutableListOf() }.add(resultSet.toEvidence())
                }
                evidenceByFact
            }
        }
    }

    private fun Connection.loadTimeline(scope: MemoryScope): List<MemoryGraphSnapshot.TimelineEvent> =
        prepareStatement(
            """
            select id, invalidated_at, invalidated_by_fact_id
            from memory_fact
            where scope_type = ?
              and scope_id = ?
              and invalidated_by_fact_id is not null
            order by invalidated_at desc
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, scope.type.name)
            statement.setString(2, scope.id)
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        val invalidatedAt = resultSet.instantOrNull("invalidated_at") ?: continue
                        add(
                            MemoryGraphSnapshot.TimelineEvent(
                                type = "SUPERSEDED",
                                factId = resultSet.getString("id"),
                                relatedFactId = resultSet.getString("invalidated_by_fact_id"),
                                happenedAt = invalidatedAt,
                            )
                        )
                    }
                }
            }
        }

    private fun Connection.loadWriteAttempts(
        scope: MemoryScope,
        limit: Int,
    ): List<MemoryWriteAttemptRecord> =
        prepareStatement(
            """
            select *
            from memory_write_attempt
            where scope_type = ?
              and scope_id = ?
            order by created_at desc
            limit ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, scope.type.name)
            statement.setString(2, scope.id)
            statement.setInt(3, limit.coerceAtLeast(1))
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(resultSet.toWriteAttempt())
                    }
                }
            }
        }

    private fun Connection.loadInjectionLogs(
        scope: MemoryScope,
        limit: Int,
    ): List<MemoryInjectionLogRecord> =
        prepareStatement(
            """
            select *
            from memory_injection_log
            where scope_type = ?
              and scope_id = ?
            order by created_at desc
            limit ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, scope.type.name)
            statement.setString(2, scope.id)
            statement.setInt(3, limit.coerceAtLeast(1))
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(resultSet.toInjectionLog())
                    }
                }
            }
        }

    private fun Connection.loadLatestActivityScope(): MemoryScope? =
        prepareStatement(
            """
            select scope_type, scope_id
            from (
                select scope_type, scope_id, created_at
                from memory_write_attempt
                union all
                select scope_type, scope_id, created_at
                from memory_injection_log
                union all
                select scope_type, scope_id, created_at
                from memory_fact
            )
            order by created_at desc
            limit 1
            """.trimIndent()
        ).use { statement ->
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    null
                } else {
                    MemoryScope(
                        type = ru.souz.agent.memory.MemoryScopeType.valueOf(resultSet.getString("scope_type")),
                        id = resultSet.getString("scope_id"),
                    )
                }
            }
        }

    private fun Connection.countByScope(tableName: String, scope: MemoryScope): Int =
        prepareStatement(
            "select count(*) from $tableName where scope_type = ? and scope_id = ?"
        ).use { statement ->
            statement.setString(1, scope.type.name)
            statement.setString(2, scope.id)
            statement.executeQuery().use { resultSet ->
                resultSet.next()
                resultSet.getInt(1)
            }
        }

    private fun ResultSet.toEntity(): MemoryEntityRecord =
        MemoryEntityRecord(
            id = getString("id"),
            scope = MemoryScope(
                type = ru.souz.agent.memory.MemoryScopeType.valueOf(getString("scope_type")),
                id = getString("scope_id"),
            ),
            entityType = getString("entity_type"),
            canonicalName = getString("canonical_name"),
            displayName = getString("display_name"),
            normalizedKey = getString("normalized_key"),
            status = getString("status"),
            createdAt = instant("created_at"),
            updatedAt = instant("updated_at"),
        )

    private fun ResultSet.toFact(): MemoryFactRecord =
        MemoryFactRecord(
            id = getString("id"),
            scope = MemoryScope(
                type = ru.souz.agent.memory.MemoryScopeType.valueOf(getString("scope_type")),
                id = getString("scope_id"),
            ),
            subjectEntityId = getString("subject_entity_id"),
            predicate = getString("predicate"),
            objectKind = MemoryObjectKind.valueOf(getString("object_kind")),
            objectEntityId = getString("object_entity_id"),
            objectValueText = getString("object_value_text"),
            objectValueJson = getString("object_value_json"),
            slotKey = getString("slot_key"),
            confidence = getDouble("confidence"),
            status = MemoryFactStatus.valueOf(getString("status")),
            reasonToStore = getString("reason_to_store"),
            createdAt = instant("created_at"),
            validFrom = instant("valid_from"),
            invalidatedAt = instantOrNull("invalidated_at"),
            invalidatedByFactId = getString("invalidated_by_fact_id"),
            originEpisodeId = getString("origin_episode_id"),
            writerVersion = getString("writer_version"),
        )

    private fun ResultSet.toWriteAttempt(): MemoryWriteAttemptRecord =
        MemoryWriteAttemptRecord(
            id = getString("id"),
            scope = MemoryScope(
                type = ru.souz.agent.memory.MemoryScopeType.valueOf(getString("scope_type")),
                id = getString("scope_id"),
            ),
            turnRef = getString("turn_ref"),
            triggerType = MemoryTriggerType.valueOf(getString("trigger_type")),
            inputExcerpt = getString("input_excerpt"),
            candidatesJson = getString("candidates_json"),
            acceptedCount = getInt("accepted_count"),
            rejectedCount = getInt("rejected_count"),
            rejectionReasonsJson = getString("rejection_reasons_json"),
            createdAt = instant("created_at"),
        )

    private fun ResultSet.toInjectionLog(): MemoryInjectionLogRecord =
        MemoryInjectionLogRecord(
            id = getString("id"),
            scope = MemoryScope(
                type = ru.souz.agent.memory.MemoryScopeType.valueOf(getString("scope_type")),
                id = getString("scope_id"),
            ),
            turnRef = getString("turn_ref"),
            queryExcerpt = getString("query_excerpt"),
            selectedRecordIdsJson = getString("selected_record_ids_json"),
            renderedPacket = getString("rendered_packet"),
            estimatedTokens = getInt("estimated_tokens"),
            createdAt = instant("created_at"),
        )

    private fun ResultSet.toEvidence(): MemoryEvidenceRecord =
        MemoryEvidenceRecord(
            id = getString("id"),
            scope = MemoryScope(
                type = ru.souz.agent.memory.MemoryScopeType.valueOf(getString("scope_type")),
                id = getString("scope_id"),
            ),
            evidenceType = ru.souz.agent.memory.MemoryEvidenceType.valueOf(getString("evidence_type")),
            sourceRef = getString("source_ref"),
            sourceHash = getString("source_hash"),
            contentExcerpt = getString("content_excerpt"),
            contentJson = getString("content_json"),
            createdAt = instant("created_at"),
        )

    private fun ResultSet.toEpisode(): MemoryEpisodeRecord =
        MemoryEpisodeRecord(
            id = getString("id"),
            scope = MemoryScope(
                type = ru.souz.agent.memory.MemoryScopeType.valueOf(getString("scope_type")),
                id = getString("scope_id"),
            ),
            title = getString("title"),
            summary = getString("summary"),
            status = getString("status"),
            startedAt = instant("started_at"),
            endedAt = instantOrNull("ended_at"),
            lastTouchedAt = instant("last_touched_at"),
            nextAction = getString("next_action"),
            importance = getObject("importance")?.let { getDouble("importance") },
        )

    private fun ResultSet.toEmbeddingDoc(): MemoryEmbeddingDocRecord =
        MemoryEmbeddingDocRecord(
            id = getString("id"),
            docType = MemoryDocType.valueOf(getString("doc_type")),
            sourceRecordType = getString("source_record_type"),
            sourceRecordId = getString("source_record_id"),
            scope = MemoryScope(
                type = ru.souz.agent.memory.MemoryScopeType.valueOf(getString("scope_type")),
                id = getString("scope_id"),
            ),
            text = getString("text"),
            status = getString("status"),
            embeddingModelFingerprint = getString("embedding_model_fingerprint"),
            createdAt = instant("created_at"),
            updatedAt = instant("updated_at"),
        )

    private fun ResultSet.instant(column: String): Instant =
        Instant.parse(getString(column))

    private fun ResultSet.instantOrNull(column: String): Instant? =
        getString(column)?.let(Instant::parse)

    private fun java.sql.PreparedStatement.setInstantText(index: Int, value: Instant) {
        setString(index, value.toString())
    }

    private fun java.sql.PreparedStatement.setNullableInstantText(index: Int, value: Instant?) {
        if (value == null) {
            setNull(index, Types.VARCHAR)
        } else {
            setString(index, value.toString())
        }
    }

    private fun java.sql.PreparedStatement.setNullableString(index: Int, value: String?) {
        if (value == null) {
            setNull(index, Types.VARCHAR)
        } else {
            setString(index, value)
        }
    }

    private fun newId(): String = UUID.randomUUID().toString()

    private data class SqliteMigration(
        val version: Int,
        val name: String,
        val resourcePath: String? = null,
    )

    private companion object {
        const val LEGACY_MEMORY_EVIDENCE_COMPAT_VERSION = 2

        val CURRENT_MEMORY_EVIDENCE_COLUMNS: Set<String> = setOf(
            "id",
            "scope_type",
            "scope_id",
            "evidence_type",
            "source_ref",
            "source_hash",
            "content_excerpt",
            "content_json",
            "created_at",
        )

        val LEGACY_MEMORY_EVIDENCE_COLUMNS: Set<String> = setOf(
            "id",
            "fact_id",
            "source_type",
            "source_ref",
            "excerpt",
            "confidence",
            "created_at",
        )

        val SQLITE_MIGRATIONS: List<SqliteMigration> = listOf(
            SqliteMigration(
                version = 1,
                name = "db/memory/sqlite/V1__memory_core.sql",
                resourcePath = "db/memory/sqlite/V1__memory_core.sql",
            ),
            SqliteMigration(
                version = LEGACY_MEMORY_EVIDENCE_COMPAT_VERSION,
                name = "db/memory/sqlite/V2__legacy_memory_evidence_compat",
            ),
        )
    }
}
