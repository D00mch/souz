package ru.souz.backend.storage.postgres

import java.sql.Connection
import java.sql.ResultSet
import java.sql.Types
import java.util.UUID
import javax.sql.DataSource
import ru.souz.agent.memory.MemoryEntityRecord
import ru.souz.agent.memory.MemoryEvidenceRecord
import ru.souz.agent.memory.MemoryFactRecord
import ru.souz.agent.memory.MemoryFactStatus
import ru.souz.agent.memory.MemoryObjectKind
import ru.souz.agent.memory.MemoryScope
import ru.souz.agent.memory.MemoryScopeType
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

    override suspend fun listActiveFacts(
        userId: String,
        scope: MemoryScope,
    ): List<MemoryFactRecord> = dataSource.read { connection ->
        connection.prepareStatement(
            """
            select *
            from memory_fact
            where user_id = ?
              and scope_type = ?
              and scope_id = ?
              and status = ?
            order by created_at desc
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, userId)
            statement.setString(2, scope.type.name)
            statement.setString(3, scope.id)
            statement.setString(4, MemoryFactStatus.ACTIVE.name)
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(resultSet.toFact())
                    }
                }
            }
        }
    }

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

    private fun java.sql.PreparedStatement.setNullableString(index: Int, value: String?) {
        if (value == null) {
            setNull(index, Types.VARCHAR)
        } else {
            setString(index, value)
        }
    }

    private fun newId(): String = UUID.randomUUID().toString()
}
