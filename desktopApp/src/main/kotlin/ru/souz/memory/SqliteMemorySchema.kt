package ru.souz.memory

import java.sql.Connection
import java.time.Instant

internal class SqliteMemorySchema(
    private val legacyOwnerMigrationTarget: MemoryOwnerId?,
) {
    fun ensureCurrentSchema(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                create table if not exists schema_migrations (
                    version integer primary key,
                    name text not null,
                    checksum text not null,
                    applied_at text not null
                )
                """.trimIndent()
            )
        }
        applyMigration(connection, 1, "initial_memory_schema", MIGRATION_V1)
        applyMigration(connection, 2, "typed_memory_columns", MIGRATION_V2)
        if (legacyOwnerMigrationTarget != null) {
            applyMigration(connection, 3, "desktop_legacy_owner_migration", emptyList())
        }
        applyMigration(connection, 4, "legacy_canonical_key_normalization", MIGRATION_V4)
        applyMigration(connection, 5, "embedding_content_hash", emptyList())
        applyMigration(connection, 6, "memory_fact_supersedes", MIGRATION_V6)
        adoptSingleExistingOwner(connection, legacyOwnerMigrationTarget)
        connection.createStatement().use { statement -> statement.execute("pragma user_version = 6") }
    }

    private fun applyMigration(
        connection: Connection,
        version: Int,
        name: String,
        statements: List<String>,
    ) {
        val applied = connection.prepareStatement("select 1 from schema_migrations where version = ?").use { statement ->
            statement.setInt(1, version)
            statement.executeQuery().use { it.next() }
        }
        if (applied) return
        if (version == 2) {
            ensureTypedMemoryColumns(connection)
        }
        if (version == 3) {
            migrateLegacyOwner(connection, legacyOwnerMigrationTarget)
        }
        if (version == 4) {
            normalizeLegacyCanonicalKeys(connection)
        }
        if (version == 5) {
            ensureEmbeddingContentHashColumns(connection)
        }
        statements.forEach { sql ->
            connection.createStatement().use { statement -> statement.execute(sql) }
        }
        connection.prepareStatement(
            "insert into schema_migrations(version, name, checksum, applied_at) values (?, ?, ?, ?)"
        ).use { statement ->
            statement.setInt(1, version)
            statement.setString(2, name)
            statement.setString(3, statements.joinToString("\n").hashCode().toString())
            statement.setString(4, Instant.now().toString())
            statement.executeUpdate()
        }
    }

    private fun migrateLegacyOwner(connection: Connection, targetOwnerId: MemoryOwnerId?) {
        val target = targetOwnerId?.value?.trim()?.takeIf(String::isNotBlank) ?: return
        if (target == LEGACY_OWNER_ID) return
        connection.ownerTables().forEach { table ->
            connection.prepareStatement("update $table set owner_id = ? where owner_id = ?").use { statement ->
                statement.setString(1, target)
                statement.setString(2, LEGACY_OWNER_ID)
                statement.executeUpdate()
            }
        }
    }

    private fun adoptSingleExistingOwner(connection: Connection, targetOwnerId: MemoryOwnerId?) {
        val target = targetOwnerId?.value?.trim()?.takeIf(String::isNotBlank) ?: return
        val existingOwners = connection.distinctOwnerIds()
        if (existingOwners.size != 1) return
        val existing = existingOwners.single()
        if (existing == target) return
        connection.ownerTables().forEach { table ->
            connection.prepareStatement("update $table set owner_id = ? where owner_id = ?").use { statement ->
                statement.setString(1, target)
                statement.setString(2, existing)
                statement.executeUpdate()
            }
        }
    }

    private fun Connection.distinctOwnerIds(): Set<String> {
        val tables = ownerTables()
        if (tables.isEmpty()) return emptySet()
        val unionSql = tables.joinToString("\nunion\n") { table ->
            "select owner_id from $table where owner_id is not null and trim(owner_id) != ''"
        }
        return prepareStatement(unionSql).use { statement ->
            statement.executeQuery().use { rs ->
                buildSet {
                    while (rs.next()) {
                        add(rs.getString("owner_id"))
                    }
                }
            }
        }
    }

    private fun Connection.ownerTables(): List<String> =
        OWNER_TABLES.filter { table -> hasTable(table) }

    private fun Connection.hasTable(table: String): Boolean =
        prepareStatement(
            """
            select 1
            from sqlite_master
            where type = 'table' and name = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, table)
            statement.executeQuery().use { it.next() }
        }

    private fun normalizeLegacyCanonicalKeys(connection: Connection) {
        val rows = connection.prepareStatement(
            """
            select id, owner_id, scope_type, scope_id, kind, title, body, slot_key, status
            from memory_facts
            where slot_key is not null
            order by updated_at desc, id asc
            """.trimIndent()
        ).use { statement ->
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            LegacyCanonicalKeyRow(
                                id = rs.getString("id"),
                                ownerId = rs.getString("owner_id") ?: LEGACY_OWNER_ID,
                                scopeType = rs.getString("scope_type"),
                                scopeId = rs.getString("scope_id"),
                                kind = MemoryFactKind.valueOf(rs.getString("kind")),
                                title = rs.getString("title"),
                                body = rs.getString("body"),
                                slotKey = rs.getString("slot_key"),
                                status = rs.getString("status"),
                            )
                        )
                    }
                }
            }
        }

        rows.forEach { row ->
            val canonicalKey = normalizeCanonicalKey(row.slotKey)
                ?.takeUnless { key ->
                    row.status == MemoryFactStatus.ACTIVE.name &&
                        connection.hasActiveCanonicalKeyConflict(row, key)
                }
            connection.prepareStatement(
                """
                update memory_facts
                set canonical_key = ?,
                    content_hash = ?
                where id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, canonicalKey)
                statement.setString(2, stableMemoryContentHash(row.title, row.body, row.kind, canonicalKey))
                statement.setString(3, row.id)
                statement.executeUpdate()
            }
        }
    }

    private fun Connection.hasActiveCanonicalKeyConflict(
        row: LegacyCanonicalKeyRow,
        canonicalKey: String,
    ): Boolean =
        prepareStatement(
            """
            select 1 from memory_facts
            where id != ?
              and owner_id = ?
              and scope_type = ?
              and scope_id = ?
              and canonical_key = ?
              and status = ?
            limit 1
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, row.id)
            statement.setString(2, row.ownerId)
            statement.setString(3, row.scopeType)
            statement.setString(4, row.scopeId)
            statement.setString(5, canonicalKey)
            statement.setString(6, MemoryFactStatus.ACTIVE.name)
            statement.executeQuery().use { it.next() }
        }

    private fun ensureTypedMemoryColumns(connection: Connection) {
        connection.addColumnIfMissing("memory_source_events", "owner_id", "text not null default '$LEGACY_OWNER_ID'")
        connection.addColumnIfMissing("memory_facts", "owner_id", "text not null default '$LEGACY_OWNER_ID'")
        connection.addColumnIfMissing("memory_facts", "canonical_key", "text")
        connection.addColumnIfMissing("memory_facts", "validity", "text not null default '${MemoryFactValidity.VALID.name}'")
        connection.addColumnIfMissing("memory_facts", "retention", "text not null default '${MemoryRetention.DURABLE.name}'")
        connection.addColumnIfMissing("memory_facts", "sensitivity", "text not null default '${MemorySensitivity.NORMAL.name}'")
        connection.addColumnIfMissing("memory_facts", "importance", "real not null default 1.0")
        connection.addColumnIfMissing("memory_facts", "version", "integer not null default 1")
        connection.addColumnIfMissing("memory_facts", "content_hash", "text")
        connection.addColumnIfMissing("memory_facts", "last_observed_at", "text")
        connection.addColumnIfMissing("memory_fact_embeddings", "content_hash", "text")
        connection.addColumnIfMissing("memory_fact_embeddings", "created_at", "text")
        connection.createStatement().use { statement ->
            statement.execute("update memory_facts set canonical_key = slot_key where canonical_key is null and slot_key is not null")
            statement.execute("update memory_facts set content_hash = lower(hex(randomblob(16))) where content_hash is null")
            statement.execute("update memory_facts set last_observed_at = updated_at where last_observed_at is null")
            statement.execute("update memory_fact_embeddings set created_at = updated_at where created_at is null")
        }
    }

    private fun ensureEmbeddingContentHashColumns(connection: Connection) {
        connection.addColumnIfMissing("memory_fact_embeddings", "content_hash", "text")
    }

    private fun Connection.addColumnIfMissing(table: String, column: String, definition: String) {
        val exists = prepareStatement("pragma table_info($table)").use { statement ->
            statement.executeQuery().use { rs ->
                var found = false
                while (rs.next()) {
                    if (rs.getString("name").equals(column, ignoreCase = true)) found = true
                }
                found
            }
        }
        if (!exists) {
            createStatement().use { it.execute("alter table $table add column $column $definition") }
        }
    }

    private data class LegacyCanonicalKeyRow(
        val id: String,
        val ownerId: String,
        val scopeType: String,
        val scopeId: String,
        val kind: MemoryFactKind,
        val title: String,
        val body: String,
        val slotKey: String,
        val status: String,
    )

    private companion object {
        private val MIGRATION_V1 = listOf(
            """
            create table if not exists memory_source_events (
                id text primary key,
                scope_type text not null,
                scope_id text not null,
                source_type text not null,
                source_ref text,
                text text not null,
                metadata_json text not null default '{}',
                created_at text not null
            )
            """.trimIndent(),
            """
            create table if not exists memory_facts (
                id text primary key,
                scope_type text not null,
                scope_id text not null,
                kind text not null,
                title text not null,
                body text not null,
                slot_key text,
                status text not null,
                confidence real not null,
                pinned integer not null,
                created_by text not null,
                created_at text not null,
                updated_at text not null,
                supersedes_fact_id text
            )
            """.trimIndent(),
            """
            create table if not exists memory_fact_evidence (
                fact_id text not null,
                source_event_id text not null,
                evidence_text text,
                primary key (fact_id, source_event_id)
            )
            """.trimIndent(),
            """
            create table if not exists memory_fact_embeddings (
                fact_id text primary key,
                embedding_model text not null,
                embedding_blob blob not null,
                dimension integer not null,
                updated_at text not null
            )
            """.trimIndent(),
            "create index if not exists memory_facts_scope_idx on memory_facts(scope_type, scope_id, status)",
            "create index if not exists memory_facts_status_idx on memory_facts(status)",
            "create index if not exists memory_facts_slot_idx on memory_facts(scope_type, scope_id, slot_key)",
            "create index if not exists memory_facts_updated_idx on memory_facts(updated_at desc)",
            "create index if not exists memory_source_events_scope_idx on memory_source_events(scope_type, scope_id, created_at desc)",
            "create unique index if not exists memory_active_slot_unique on memory_facts(scope_type, scope_id, slot_key) where status = 'ACTIVE' and slot_key is not null",
        )
        private val MIGRATION_V2 = listOf(
            "create index if not exists memory_facts_owner_scope_idx on memory_facts(owner_id, scope_type, scope_id, status)",
            "create index if not exists memory_facts_owner_canonical_idx on memory_facts(owner_id, scope_type, scope_id, canonical_key)",
            "create unique index if not exists memory_active_canonical_unique on memory_facts(owner_id, scope_type, scope_id, canonical_key) where status = 'ACTIVE' and canonical_key is not null",
            "create index if not exists memory_facts_content_hash_idx on memory_facts(content_hash)",
            "create index if not exists memory_source_events_owner_scope_idx on memory_source_events(owner_id, scope_type, scope_id, created_at desc)",
            """
            create table if not exists memory_fact_stats (
                fact_id text primary key,
                last_retrieved_at text,
                retrieval_count integer not null default 0
            )
            """.trimIndent(),
            """
            create table if not exists memory_index_jobs (
                fact_id text not null,
                owner_id text not null,
                embedding_model text not null,
                content_hash text not null,
                status text not null,
                attempt_count integer not null default 0,
                next_attempt_at text not null,
                last_error_code text,
                lease_owner text,
                lease_expires_at text,
                created_at text not null,
                updated_at text not null,
                primary key (fact_id, embedding_model, content_hash)
            )
            """.trimIndent(),
            "create index if not exists memory_index_jobs_status_idx on memory_index_jobs(status, next_attempt_at)",
            """
            create table if not exists memory_operation_log (
                id text primary key,
                fact_id text,
                owner_id text not null,
                type text not null,
                reason text not null,
                created_at text not null
            )
            """.trimIndent(),
            "create index if not exists memory_operation_log_owner_idx on memory_operation_log(owner_id, created_at desc)",
            """
            create table if not exists memory_tombstones (
                id text primary key,
                owner_id text not null,
                scope_type text not null,
                scope_id text not null,
                canonical_key text,
                subject_key text,
                reason text not null,
                created_at text not null,
                expires_at text
            )
            """.trimIndent(),
            "create index if not exists memory_tombstones_lookup_idx on memory_tombstones(owner_id, scope_type, scope_id, canonical_key, subject_key)",
            """
            create table if not exists memory_maintenance_jobs (
                cluster_key text primary key,
                owner_id text not null,
                status text not null,
                priority integer not null default 0,
                latest_dirty_at text not null,
                reasons text not null default '',
                lease_owner text,
                lease_expires_at text,
                attempt_count integer not null default 0,
                next_attempt_at text not null,
                created_at text not null,
                updated_at text not null
            )
            """.trimIndent(),
            "create index if not exists memory_maintenance_jobs_status_idx on memory_maintenance_jobs(status, priority desc, next_attempt_at)",
            """
            insert or ignore into memory_maintenance_jobs(
                cluster_key,
                owner_id,
                status,
                priority,
                latest_dirty_at,
                reasons,
                next_attempt_at,
                created_at,
                updated_at
            )
            select
                'legacy-chat:' || owner_id || ':' || scope_id,
                owner_id,
                'PENDING',
                10,
                max(updated_at),
                'legacy_chat_fact_migration',
                strftime('%Y-%m-%dT%H:%M:%fZ', 'now'),
                strftime('%Y-%m-%dT%H:%M:%fZ', 'now'),
                strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
            from memory_facts
            where scope_type in ('chat', 'thread')
            group by owner_id, scope_id
            """.trimIndent(),
        )
        private val MIGRATION_V4 = listOf(
            "drop index if exists memory_active_slot_unique",
        )
        private val MIGRATION_V6 = listOf(
            """
            create table if not exists memory_fact_supersedes (
                replacement_fact_id text not null,
                superseded_fact_id text not null,
                owner_id text not null,
                reason text not null,
                created_at text not null,
                primary key (replacement_fact_id, superseded_fact_id)
            )
            """.trimIndent(),
            "create index if not exists memory_fact_supersedes_superseded_idx on memory_fact_supersedes(superseded_fact_id)",
        )
        private val OWNER_TABLES = listOf(
            "memory_source_events",
            "memory_facts",
            "memory_fact_supersedes",
            "memory_index_jobs",
            "memory_operation_log",
            "memory_tombstones",
            "memory_maintenance_jobs",
        )
    }
}
