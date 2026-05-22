package ru.souz.backend.storage.postgres

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import ru.souz.agent.memory.MemoryDocType
import ru.souz.agent.memory.MemoryEmbeddingDocRecord
import ru.souz.agent.memory.MemoryEntityRecord
import ru.souz.agent.memory.MemoryEpisodeRecord
import ru.souz.agent.memory.MemoryEvidenceRecord
import ru.souz.agent.memory.MemoryFactRecord
import ru.souz.agent.memory.MemoryFactStatus
import ru.souz.agent.memory.MemoryGraphSnapshot
import ru.souz.agent.memory.MemoryObjectKind
import ru.souz.agent.memory.MemoryScope
import ru.souz.agent.memory.MemoryScopeType
import ru.souz.agent.memory.MemoryTriggerType
import ru.souz.backend.memory.BackendMemoryStore

class PostgresBackendMemoryStore(
    private val dataSource: DataSource,
) : BackendMemoryStore {
    override suspend fun resolveOrUpsertEntity(
        userId: String,
        entity: MemoryEntityRecord,
        aliases: List<String>,
    ): MemoryEntityRecord = dataSource.write { connection ->
        connection.ensureUser(userId)
        val entityId = connection.findEntityId(userId, entity.normalizedKey) ?: entity.id ?: newId()
        connection.prepareStatement(
            """
            insert into memory_entity(
                id,
                user_id,
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
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (user_id, normalized_key) do update set
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
            statement.setString(2, userId)
            statement.setString(3, entity.scope.type.name)
            statement.setString(4, entity.scope.id)
            statement.setString(5, entity.entityType)
            statement.setString(6, entity.canonicalName)
            statement.setString(7, entity.displayName)
            statement.setString(8, entity.normalizedKey)
            statement.setString(9, entity.status)
            statement.setInstant(10, entity.createdAt)
            statement.setInstant(11, entity.updatedAt)
            statement.executeUpdate()
        }
        connection.selectEntity(userId, entity.normalizedKey)
            ?: error("Stored entity is missing for normalized key ${entity.normalizedKey}.")
    }

    override suspend fun insertEvidence(
        userId: String,
        evidence: MemoryEvidenceRecord,
    ): MemoryEvidenceRecord = dataSource.write { connection ->
        connection.ensureUser(userId)
        val stored = evidence.copy(id = evidence.id ?: newId())
        connection.prepareStatement(
            """
            insert into memory_evidence(
                id,
                user_id,
                scope_type,
                scope_id,
                evidence_type,
                source_ref,
                source_hash,
                content_excerpt,
                content_json,
                created_at
            )
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, stored.id)
            statement.setString(2, userId)
            statement.setString(3, stored.scope.type.name)
            statement.setString(4, stored.scope.id)
            statement.setString(5, stored.evidenceType.name)
            statement.setString(6, stored.sourceRef)
            statement.setNullableString(7, stored.sourceHash)
            statement.setNullableString(8, stored.contentExcerpt)
            statement.setJson(9, stored.contentJson)
            statement.setInstant(10, stored.createdAt)
            statement.executeUpdate()
        }
        stored
    }

    override suspend fun insertFact(
        userId: String,
        fact: MemoryFactRecord,
        evidenceIds: List<String>,
    ): MemoryFactRecord = dataSource.write { connection ->
        connection.ensureUser(userId)
        val stored = fact.copy(id = fact.id ?: newId())
        stored.slotKey?.let { slotKey ->
            connection.prepareStatement(
                """
                update memory_fact
                set status = ?,
                    invalidated_at = ?
                where user_id = ?
                  and slot_key = ?
                  and status = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, MemoryFactStatus.SUPERSEDED.name)
                statement.setInstant(2, stored.validFrom)
                statement.setString(3, userId)
                statement.setString(4, slotKey)
                statement.setString(5, MemoryFactStatus.ACTIVE.name)
                statement.executeUpdate()
            }
        }
        connection.prepareStatement(
            """
            insert into memory_fact(
                id,
                user_id,
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
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, stored.id)
            statement.setString(2, userId)
            statement.setString(3, stored.scope.type.name)
            statement.setString(4, stored.scope.id)
            statement.setString(5, stored.subjectEntityId)
            statement.setString(6, stored.predicate)
            statement.setString(7, stored.objectKind.name)
            statement.setNullableString(8, stored.objectEntityId)
            statement.setNullableString(9, stored.objectValueText)
            statement.setJson(10, stored.objectValueJson)
            statement.setNullableString(11, stored.slotKey)
            statement.setDouble(12, stored.confidence)
            statement.setString(13, stored.status.name)
            statement.setString(14, stored.reasonToStore)
            statement.setInstant(15, stored.createdAt)
            statement.setInstant(16, stored.validFrom)
            statement.setInstant(17, stored.invalidatedAt)
            statement.setNullableString(18, stored.invalidatedByFactId)
            statement.setNullableString(19, stored.originEpisodeId)
            statement.setNullableString(20, stored.writerVersion)
            statement.executeUpdate()
        }
        evidenceIds.distinct().forEach { evidenceId ->
            connection.prepareStatement(
                """
                insert into memory_fact_evidence(
                    id,
                    user_id,
                    fact_id,
                    evidence_id,
                    support_type,
                    weight,
                    created_at
                )
                values (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, newId())
                statement.setString(2, userId)
                statement.setString(3, stored.id)
                statement.setString(4, evidenceId)
                statement.setString(5, "PRIMARY")
                statement.setDouble(6, 1.0)
                statement.setInstant(7, stored.createdAt)
                statement.executeUpdate()
            }
        }
        stored.slotKey?.let { slotKey ->
            connection.prepareStatement(
                """
                update memory_fact
                set invalidated_by_fact_id = ?
                where user_id = ?
                  and slot_key = ?
                  and status = ?
                  and id <> ?
                  and invalidated_by_fact_id is null
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, stored.id)
                statement.setString(2, userId)
                statement.setString(3, slotKey)
                statement.setString(4, MemoryFactStatus.SUPERSEDED.name)
                statement.setString(5, stored.id)
                statement.executeUpdate()
            }
        }
        stored
    }

    override suspend fun listEntitiesByIds(
        userId: String,
        entityIds: Set<String>,
    ): List<MemoryEntityRecord> = dataSource.read { connection ->
        if (entityIds.isEmpty()) {
            return@read emptyList()
        }
        val placeholders = entityIds.joinToString(",") { "?" }
        connection.prepareStatement(
            """
            select *
            from memory_entity
            where user_id = ?
              and id in ($placeholders)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, userId)
            entityIds.forEachIndexed { index, entityId ->
                statement.setString(index + 2, entityId)
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

    override suspend fun listFacts(
        userId: String,
        scope: MemoryScope?,
        statuses: Set<MemoryFactStatus>,
    ): List<MemoryFactRecord> = dataSource.read { connection ->
        val statusValues = statuses.ifEmpty { setOf(MemoryFactStatus.ACTIVE) }.map { it.name }
        val placeholders = statusValues.joinToString(",") { "?" }
        val sql = buildString {
            append(
                """
                select *
                from memory_fact
                where user_id = ?
                  and status in ($placeholders)
                """.trimIndent()
            )
            if (scope != null) {
                append(" and scope_type = ? and scope_id = ?")
            }
            append(" order by created_at desc")
        }
        connection.prepareStatement(sql).use { statement ->
            var index = 1
            statement.setString(index++, userId)
            statusValues.forEach { status -> statement.setString(index++, status) }
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

    override suspend fun insertEpisode(
        userId: String,
        episode: MemoryEpisodeRecord,
    ): MemoryEpisodeRecord = dataSource.write { connection ->
        connection.ensureUser(userId)
        val stored = episode.copy(id = episode.id ?: newId())
        connection.prepareStatement(
            """
            insert into memory_episode(
                id,
                user_id,
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
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, stored.id)
            statement.setString(2, userId)
            statement.setString(3, stored.scope.type.name)
            statement.setString(4, stored.scope.id)
            statement.setString(5, stored.title)
            statement.setString(6, stored.summary)
            statement.setString(7, stored.status)
            statement.setInstant(8, stored.startedAt)
            statement.setInstant(9, stored.endedAt)
            statement.setInstant(10, stored.lastTouchedAt)
            statement.setNullableString(11, stored.nextAction)
            val importance = stored.importance
            if (importance == null) {
                statement.setNull(12, Types.DOUBLE)
            } else {
                statement.setDouble(12, importance)
            }
            statement.executeUpdate()
        }
        stored
    }

    override suspend fun listEpisodes(
        userId: String,
        scopes: List<MemoryScope>,
        statuses: Set<String>,
    ): List<MemoryEpisodeRecord> = dataSource.read { connection ->
        val statusValues = statuses.ifEmpty { setOf("ACTIVE") }
        val placeholders = statusValues.joinToString(",") { "?" }
        val sql = buildString {
            append(
                """
                select *
                from memory_episode
                where user_id = ?
                  and status in ($placeholders)
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
            statement.setString(index++, userId)
            statusValues.forEach { status -> statement.setString(index++, status) }
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

    override suspend fun replaceEmbeddingDocs(
        userId: String,
        docs: List<MemoryEmbeddingDocRecord>,
    ) = dataSource.write { connection ->
        connection.ensureUser(userId)
        connection.prepareStatement("delete from memory_embedding_doc where user_id = ?").use { statement ->
            statement.setString(1, userId)
            statement.executeUpdate()
        }
        docs.forEach { doc ->
            val stored = doc.copy(id = doc.id ?: newId())
            connection.prepareStatement(
                """
                insert into memory_embedding_doc(
                    id,
                    user_id,
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
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, stored.id)
                statement.setString(2, userId)
                statement.setString(3, stored.docType.name)
                statement.setString(4, stored.sourceRecordType)
                statement.setString(5, stored.sourceRecordId)
                statement.setString(6, stored.scope.type.name)
                statement.setString(7, stored.scope.id)
                statement.setString(8, stored.text)
                statement.setString(9, stored.status)
                statement.setNullableString(10, stored.embeddingModelFingerprint)
                statement.setInstant(11, stored.createdAt)
                statement.setInstant(12, stored.updatedAt)
                statement.executeUpdate()
            }
        }
    }

    override suspend fun listEmbeddingDocs(
        userId: String,
        scopes: List<MemoryScope>,
        docTypes: Set<MemoryDocType>,
        fingerprint: String?,
    ): List<MemoryEmbeddingDocRecord> = dataSource.read { connection ->
        val sql = buildString {
            append(
                """
                select *
                from memory_embedding_doc
                where user_id = ?
                  and status = 'ACTIVE'
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
            statement.setString(index++, userId)
            if (fingerprint != null) {
                statement.setString(index++, fingerprint)
            }
            docTypes.forEach { docType -> statement.setString(index++, docType.name) }
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
        userId: String,
        scope: MemoryScope,
        turnRef: String?,
        triggerType: MemoryTriggerType,
        inputExcerpt: String?,
        candidatesJson: String,
        acceptedCount: Int,
        rejectedCount: Int,
        rejectionReasonsJson: String?,
        createdAt: Instant,
    ): String = dataSource.write { connection ->
        connection.ensureUser(userId)
        val id = newId()
        connection.prepareStatement(
            """
            insert into memory_write_attempt(
                id,
                user_id,
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
            values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?::jsonb, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, id)
            statement.setString(2, userId)
            statement.setString(3, scope.type.name)
            statement.setString(4, scope.id)
            statement.setNullableString(5, turnRef)
            statement.setString(6, triggerType.name)
            statement.setNullableString(7, inputExcerpt)
            statement.setString(8, candidatesJson)
            statement.setInt(9, acceptedCount)
            statement.setInt(10, rejectedCount)
            statement.setNullableString(11, rejectionReasonsJson)
            statement.setInstant(12, createdAt)
            statement.executeUpdate()
        }
        id
    }

    override suspend fun logInjection(
        userId: String,
        scope: MemoryScope,
        turnRef: String?,
        queryExcerpt: String?,
        selectedRecordIds: List<String>,
        renderedPacket: String,
        estimatedTokens: Int,
        createdAt: Instant,
    ): String = dataSource.write { connection ->
        connection.ensureUser(userId)
        val id = newId()
        connection.prepareStatement(
            """
            insert into memory_injection_log(
                id,
                user_id,
                scope_type,
                scope_id,
                turn_ref,
                query_excerpt,
                selected_record_ids_json,
                rendered_packet,
                estimated_tokens,
                created_at
            )
            values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, id)
            statement.setString(2, userId)
            statement.setString(3, scope.type.name)
            statement.setString(4, scope.id)
            statement.setNullableString(5, turnRef)
            statement.setNullableString(6, queryExcerpt)
            statement.setJson(7, jacksonObjectMapper().writeValueAsString(selectedRecordIds))
            statement.setString(8, renderedPacket)
            statement.setInt(9, estimatedTokens)
            statement.setInstant(10, createdAt)
            statement.executeUpdate()
        }
        id
    }

    override suspend fun graphSnapshot(
        userId: String,
        scope: MemoryScope,
    ): MemoryGraphSnapshot = dataSource.read { connection ->
        val facts = connection.loadFacts(userId, scope, setOf(MemoryFactStatus.ACTIVE))
        val entityIds = facts.flatMap { listOfNotNull(it.subjectEntityId, it.objectEntityId) }.toSet()
        val entities = connection.loadEntities(userId, entityIds)
        val evidenceIndex = connection.loadEvidenceIndex(userId, facts.mapNotNull { it.id })
        MemoryGraphSnapshot(
            entities = entities,
            facts = facts,
            edges = facts.mapNotNull { fact ->
                val objectEntityId = fact.objectEntityId ?: return@mapNotNull null
                if (fact.objectKind != MemoryObjectKind.ENTITY) return@mapNotNull null
                MemoryGraphSnapshot.Edge(
                    factId = fact.id!!,
                    subjectEntityId = fact.subjectEntityId,
                    predicate = fact.predicate,
                    objectEntityId = objectEntityId,
                )
            },
            attributes = facts.mapNotNull { fact ->
                if (fact.objectKind == MemoryObjectKind.ENTITY) return@mapNotNull null
                MemoryGraphSnapshot.Attribute(
                    factId = fact.id!!,
                    subjectEntityId = fact.subjectEntityId,
                    predicate = fact.predicate,
                    value = fact.objectValueText ?: fact.objectValueJson.orEmpty(),
                )
            },
            timelineEvents = connection.loadTimeline(userId, scope),
            evidenceIndex = evidenceIndex,
            diagnostics = MemoryGraphSnapshot.Diagnostics(
                writeAttemptCount = connection.countByScope(userId, "memory_write_attempt", scope),
                injectionLogCount = connection.countByScope(userId, "memory_injection_log", scope),
            ),
            generatedAt = Instant.now(),
        )
    }

    override suspend fun forgetFact(
        userId: String,
        factId: String,
        at: Instant,
    ): Boolean = updateFactStatus(userId, factId, MemoryFactStatus.FORGOTTEN, at)

    override suspend fun invalidateFact(
        userId: String,
        factId: String,
        at: Instant,
    ): Boolean = updateFactStatus(userId, factId, MemoryFactStatus.INVALIDATED, at)

    private fun Connection.ensureUser(userId: String) {
        prepareStatement(
            """
            insert into users(id, created_at, last_seen_at)
            values (?, now(), now())
            on conflict (id) do update set last_seen_at = excluded.last_seen_at
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, userId)
            statement.executeUpdate()
        }
    }

    private fun Connection.findEntityId(userId: String, normalizedKey: String): String? =
        prepareStatement(
            """
            select id
            from memory_entity
            where user_id = ?
              and normalized_key = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, userId)
            statement.setString(2, normalizedKey)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.getString("id") else null
            }
        }

    private fun Connection.selectEntity(userId: String, normalizedKey: String): MemoryEntityRecord? =
        prepareStatement(
            """
            select *
            from memory_entity
            where user_id = ?
              and normalized_key = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, userId)
            statement.setString(2, normalizedKey)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toEntity() else null
            }
        }

    private fun ResultSet.toEntity(): MemoryEntityRecord =
        MemoryEntityRecord(
            id = getString("id"),
            scope = MemoryScope(
                type = MemoryScopeType.valueOf(getString("scope_type")),
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
                type = MemoryScopeType.valueOf(getString("scope_type")),
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
            invalidatedAt = getObject("invalidated_at", java.time.OffsetDateTime::class.java)?.toInstant(),
            invalidatedByFactId = getString("invalidated_by_fact_id"),
            originEpisodeId = getString("origin_episode_id"),
            writerVersion = getString("writer_version"),
        )

    private fun ResultSet.toEvidence(): MemoryEvidenceRecord =
        MemoryEvidenceRecord(
            id = getString("id"),
            scope = MemoryScope(
                type = MemoryScopeType.valueOf(getString("scope_type")),
                id = getString("scope_id"),
            ),
            evidenceType = ru.souz.agent.memory.MemoryEvidenceType.valueOf(getString("evidence_type")),
            sourceRef = getString("source_ref"),
            sourceHash = getString("source_hash"),
            contentExcerpt = getString("content_excerpt"),
            contentJson = getString("content_json"),
            createdAt = instant("created_at"),
        )

    private fun Connection.loadFacts(
        userId: String,
        scope: MemoryScope?,
        statuses: Set<MemoryFactStatus>,
    ): List<MemoryFactRecord> {
        val statusValues = statuses.ifEmpty { setOf(MemoryFactStatus.ACTIVE) }.map { it.name }
        val placeholders = statusValues.joinToString(",") { "?" }
        val sql = buildString {
            append(
                """
                select *
                from memory_fact
                where user_id = ?
                  and status in ($placeholders)
                """.trimIndent()
            )
            if (scope != null) {
                append(" and scope_type = ? and scope_id = ?")
            }
            append(" order by created_at desc")
        }
        return prepareStatement(sql).use { statement ->
            var index = 1
            statement.setString(index++, userId)
            statusValues.forEach { status -> statement.setString(index++, status) }
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

    private fun Connection.loadEntities(
        userId: String,
        entityIds: Set<String>,
    ): List<MemoryEntityRecord> {
        if (entityIds.isEmpty()) {
            return emptyList()
        }
        val placeholders = entityIds.joinToString(",") { "?" }
        return prepareStatement(
            """
            select *
            from memory_entity
            where user_id = ?
              and id in ($placeholders)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, userId)
            entityIds.forEachIndexed { index, entityId ->
                statement.setString(index + 2, entityId)
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

    private fun ResultSet.toEpisode(): MemoryEpisodeRecord =
        MemoryEpisodeRecord(
            id = getString("id"),
            scope = MemoryScope(
                type = MemoryScopeType.valueOf(getString("scope_type")),
                id = getString("scope_id"),
            ),
            title = getString("title"),
            summary = getString("summary"),
            status = getString("status"),
            startedAt = instant("started_at"),
            endedAt = getObject("ended_at", java.time.OffsetDateTime::class.java)?.toInstant(),
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
                type = MemoryScopeType.valueOf(getString("scope_type")),
                id = getString("scope_id"),
            ),
            text = getString("text"),
            status = getString("status"),
            embeddingModelFingerprint = getString("embedding_model_fingerprint"),
            createdAt = instant("created_at"),
            updatedAt = instant("updated_at"),
        )

    private fun java.sql.PreparedStatement.setNullableString(index: Int, value: String?) {
        if (value == null) {
            setNull(index, Types.VARCHAR)
        } else {
            setString(index, value)
        }
    }

    private fun Connection.loadEvidenceIndex(
        userId: String,
        factIds: List<String>,
    ): Map<String, List<MemoryEvidenceRecord>> {
        if (factIds.isEmpty()) {
            return emptyMap()
        }
        val placeholders = factIds.joinToString(",") { "?" }
        return prepareStatement(
            """
            select fe.fact_id as fact_id, e.*
            from memory_fact_evidence fe
            join memory_evidence e on e.id = fe.evidence_id
            where fe.user_id = ?
              and fe.fact_id in ($placeholders)
            order by e.created_at asc
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, userId)
            factIds.forEachIndexed { index, factId ->
                statement.setString(index + 2, factId)
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

    private fun Connection.loadTimeline(
        userId: String,
        scope: MemoryScope,
    ): List<MemoryGraphSnapshot.TimelineEvent> =
        prepareStatement(
            """
            select id, invalidated_at, invalidated_by_fact_id
            from memory_fact
            where user_id = ?
              and scope_type = ?
              and scope_id = ?
              and invalidated_by_fact_id is not null
            order by invalidated_at desc
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, userId)
            statement.setString(2, scope.type.name)
            statement.setString(3, scope.id)
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        val happenedAt = resultSet.getObject("invalidated_at", java.time.OffsetDateTime::class.java)?.toInstant()
                            ?: continue
                        add(
                            MemoryGraphSnapshot.TimelineEvent(
                                type = "SUPERSEDED",
                                factId = resultSet.getString("id"),
                                relatedFactId = resultSet.getString("invalidated_by_fact_id"),
                                happenedAt = happenedAt,
                            )
                        )
                    }
                }
            }
        }

    private fun Connection.countByScope(
        userId: String,
        tableName: String,
        scope: MemoryScope,
    ): Int =
        prepareStatement(
            "select count(*) from $tableName where user_id = ? and scope_type = ? and scope_id = ?"
        ).use { statement ->
            statement.setString(1, userId)
            statement.setString(2, scope.type.name)
            statement.setString(3, scope.id)
            statement.executeQuery().use { resultSet ->
                resultSet.next()
                resultSet.getInt(1)
            }
        }

    private fun Connection.updateFactStatus(
        userId: String,
        factId: String,
        status: MemoryFactStatus,
        at: Instant,
    ): Boolean =
        prepareStatement(
            """
            update memory_fact
            set status = ?,
                invalidated_at = ?
            where user_id = ?
              and id = ?
              and status = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, status.name)
            statement.setInstant(2, at)
            statement.setString(3, userId)
            statement.setString(4, factId)
            statement.setString(5, MemoryFactStatus.ACTIVE.name)
            statement.executeUpdate() > 0
        }

    private suspend fun updateFactStatus(
        userId: String,
        factId: String,
        status: MemoryFactStatus,
        at: Instant,
    ): Boolean = dataSource.write { connection ->
        connection.updateFactStatus(userId, factId, status, at)
    }

    private fun newId(): String = UUID.randomUUID().toString()
}
