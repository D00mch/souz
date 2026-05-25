package ru.souz.memory

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SqliteMemoryRepository(
    private val dbPath: Path,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MemoryRepository {
    private val initMutex = Mutex()
    private var initialized = false

    override suspend fun insertSourceEvent(input: NewMemorySourceEvent): String = withConnection { connection ->
        val id = UUID.randomUUID().toString()
        connection.prepareStatement(
            """
            insert into memory_source_events(
                id, scope_type, scope_id, source_type, source_ref, text, metadata_json, created_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, id)
            statement.setString(2, input.scope.type)
            statement.setString(3, input.scope.id)
            statement.setString(4, input.sourceType)
            statement.setString(5, input.sourceRef)
            statement.setString(6, input.text)
            statement.setString(7, input.metadataJson)
            statement.setString(8, input.createdAt.toString())
            statement.executeUpdate()
        }
        id
    }

    override suspend fun insertFact(
        input: NewMemoryFact,
        evidence: List<MemoryEvidenceRef>,
        embedding: FloatArray?,
        embeddingModel: String?,
    ): String = withConnection { connection ->
        val id = UUID.randomUUID().toString()
        connection.inTransaction {
            if (input.slotKey != null && input.status == MemoryFactStatus.ACTIVE) {
                prepareStatement(
                    """
                    update memory_facts
                    set status = ?, updated_at = ?
                    where scope_type = ? and scope_id = ? and slot_key = ? and status = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, MemoryFactStatus.RETIRED.name)
                    statement.setString(2, Instant.now().toString())
                    statement.setString(3, input.scope.type)
                    statement.setString(4, input.scope.id)
                    statement.setString(5, input.slotKey)
                    statement.setString(6, MemoryFactStatus.ACTIVE.name)
                    statement.executeUpdate()
                }
            }

            prepareStatement(
                """
                insert into memory_facts(
                    id, scope_type, scope_id, kind, title, body, slot_key, status, confidence,
                    pinned, created_by, created_at, updated_at, supersedes_fact_id
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, id)
                statement.setString(2, input.scope.type)
                statement.setString(3, input.scope.id)
                statement.setString(4, input.kind.name)
                statement.setString(5, input.title)
                statement.setString(6, input.body)
                statement.setString(7, input.slotKey)
                statement.setString(8, input.status.name)
                statement.setFloat(9, input.confidence)
                statement.setInt(10, if (input.pinned) 1 else 0)
                statement.setString(11, input.createdBy)
                statement.setString(12, input.createdAt.toString())
                statement.setString(13, input.updatedAt.toString())
                statement.setString(14, input.supersedesFactId)
                statement.executeUpdate()
            }
            if (evidence.isNotEmpty()) {
                prepareStatement(
                    """
                    insert into memory_fact_evidence(
                        fact_id, source_event_id, evidence_text
                    ) values (?, ?, ?)
                    """.trimIndent()
                ).use { statement ->
                    evidence.forEach { ref ->
                        statement.setString(1, id)
                        statement.setString(2, ref.sourceEventId)
                        statement.setString(3, ref.evidenceText)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
            }
            upsertEmbedding(id, embedding, embeddingModel)
        }
        id
    }

    override suspend fun getFact(factId: String): MemoryFact? = withConnection { connection ->
        connection.prepareStatement(
            """
            select * from memory_facts
            where id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, factId)
            statement.executeQuery().use { rs ->
                rs.toFactOrNull()
            }
        }
    }

    override suspend fun getFactDetails(factId: String): MemoryFactDetails? = withConnection { connection ->
        val fact = connection.prepareStatement(
            """
            select * from memory_facts
            where id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, factId)
            statement.executeQuery().use { rs -> rs.toFactOrNull() }
        } ?: return@withConnection null

        val evidence = connection.prepareStatement(
            """
            select
                e.fact_id,
                e.source_event_id,
                e.evidence_text,
                se.id as source_id,
                se.scope_type as source_scope_type,
                se.scope_id as source_scope_id,
                se.source_type as source_type,
                se.source_ref as source_ref,
                se.text as source_text,
                se.metadata_json as source_metadata_json,
                se.created_at as source_created_at
            from memory_fact_evidence e
            join memory_source_events se on se.id = e.source_event_id
            where e.fact_id = ?
            order by se.created_at desc
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, factId)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            MemoryEvidenceDetail(
                                evidence = MemoryEvidence(
                                    factId = rs.getString("fact_id"),
                                    sourceEventId = rs.getString("source_event_id"),
                                    evidenceText = rs.getString("evidence_text"),
                                ),
                                sourceEvent = MemorySourceEvent(
                                    id = rs.getString("source_id"),
                                    scope = MemoryScope(
                                        type = rs.getString("source_scope_type"),
                                        id = rs.getString("source_scope_id"),
                                    ),
                                    sourceType = rs.getString("source_type"),
                                    sourceRef = rs.getString("source_ref"),
                                    text = rs.getString("source_text"),
                                    metadataJson = rs.getString("source_metadata_json"),
                                    createdAt = Instant.parse(rs.getString("source_created_at")),
                                ),
                            )
                        )
                    }
                }
            }
        }
        MemoryFactDetails(fact = fact, evidence = evidence)
    }

    override suspend fun listFacts(filter: MemoryFactFilter): List<MemoryFact> = withConnection { connection ->
        val clauses = ArrayList<String>()
        val params = ArrayList<Any>()

        if (filter.statuses.isNotEmpty()) {
            clauses += "status in (${placeholders(filter.statuses.size)})"
            params.addAll(filter.statuses.map(MemoryFactStatus::name))
        }
        if (filter.kinds.isNotEmpty()) {
            clauses += "kind in (${placeholders(filter.kinds.size)})"
            params.addAll(filter.kinds.map(MemoryFactKind::name))
        }
        filter.scope?.let { scope ->
            clauses += "scope_type = ? and scope_id = ?"
            params.add(scope.type)
            params.add(scope.id)
        }
        filter.query?.trim()?.takeIf(String::isNotBlank)?.let { query ->
            clauses += "(title like ? or body like ?)"
            val like = "%$query%"
            params.add(like)
            params.add(like)
        }

        val sql = buildString {
            append("select * from memory_facts")
            if (clauses.isNotEmpty()) {
                append(" where ")
                append(clauses.joinToString(" and "))
            }
            append(" order by updated_at desc limit ? offset ?")
        }

        connection.prepareStatement(sql).use { statement ->
            bindParams(statement, params)
            statement.setInt(params.size + 1, filter.limit)
            statement.setInt(params.size + 2, filter.offset)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(rs.toFact())
                    }
                }
            }
        }
    }

    override suspend fun updateFact(
        fact: MemoryFact,
        expectedUpdatedAt: Instant,
        embedding: FloatArray?,
        embeddingModel: String?,
    ): MemoryFact = withConnection { connection ->
        connection.inTransaction {
            val updatedRows = prepareStatement(
                """
                update memory_facts
                set scope_type = ?,
                    scope_id = ?,
                    kind = ?,
                    title = ?,
                    body = ?,
                    slot_key = ?,
                    confidence = ?,
                    pinned = ?,
                    updated_at = ?
                where id = ? and updated_at = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, fact.scope.type)
                statement.setString(2, fact.scope.id)
                statement.setString(3, fact.kind.name)
                statement.setString(4, fact.title)
                statement.setString(5, fact.body)
                statement.setString(6, fact.slotKey)
                statement.setFloat(7, fact.confidence)
                statement.setInt(8, if (fact.pinned) 1 else 0)
                statement.setString(9, fact.updatedAt.toString())
                statement.setString(10, fact.id)
                statement.setString(11, expectedUpdatedAt.toString())
                statement.executeUpdate()
            }
            if (updatedRows == 0) {
                error("Memory fact was modified concurrently: ${fact.id}")
            }
            upsertEmbedding(fact.id, embedding, embeddingModel)
        }
        fact
    }

    override suspend fun retireFact(factId: String) {
        updateStatus(factId, MemoryFactStatus.RETIRED)
    }

    override suspend fun deleteFact(factId: String): Unit = withConnection { connection ->
        connection.inTransaction {
            val sourceEventIds = ArrayList<String>()
            prepareStatement(
                "select source_event_id from memory_fact_evidence where fact_id = ?"
            ).use { statement ->
                statement.setString(1, factId)
                statement.executeQuery().use { rs ->
                    while (rs.next()) {
                        sourceEventIds.add(rs.getString("source_event_id"))
                    }
                }
            }

            prepareStatement(
                "delete from memory_fact_embeddings where fact_id = ?"
            ).use { statement ->
                statement.setString(1, factId)
                statement.executeUpdate()
            }

            prepareStatement(
                "delete from memory_fact_evidence where fact_id = ?"
            ).use { statement ->
                statement.setString(1, factId)
                statement.executeUpdate()
            }

            prepareStatement(
                "delete from memory_facts where id = ?"
            ).use { statement ->
                statement.setString(1, factId)
                statement.executeUpdate()
            }

            sourceEventIds.distinct().forEach { deleteSourceEventIfUnused(it) }
        }
    }

    override suspend fun deleteSourceEventIfUnused(sourceEventId: String) =
        withConnection { it.deleteSourceEventIfUnused(sourceEventId) }

    override suspend fun getFactsWithoutEmbedding(
        scopes: List<MemoryScope>,
        model: String,
    ): List<MemoryFact> {
        if (scopes.isEmpty()) return emptyList()
        return withConnection { connection ->
            val scopeClause = scopes.joinToString(" or ") { "(f.scope_type = ? and f.scope_id = ?)" }
            connection.prepareStatement(
                """
                select f.* from memory_facts f
                left join memory_fact_embeddings e on e.fact_id = f.id and e.embedding_model = ?
                where f.status = ?
                  and e.fact_id is null
                  and ($scopeClause)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, model)
                statement.setString(2, MemoryFactStatus.ACTIVE.name)
                var index = 3
                scopes.forEach { scope ->
                    statement.setString(index++, scope.type)
                    statement.setString(index++, scope.id)
                }
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(rs.toFact())
                        }
                    }
                }
            }
        }
    }

    override suspend fun findActiveFactBySlotKey(
        scope: MemoryScope,
        slotKey: String,
    ): MemoryFact? = withConnection { connection ->
        connection.prepareStatement(
            """
            select * from memory_facts
            where scope_type = ?
              and scope_id = ?
              and slot_key = ?
              and status = ?
            order by updated_at desc
            limit 1
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, scope.type)
            statement.setString(2, scope.id)
            statement.setString(3, slotKey)
            statement.setString(4, MemoryFactStatus.ACTIVE.name)
            statement.executeQuery().use { rs -> rs.toFactOrNull() }
        }
    }

    override suspend fun replaceEmbedding(
        factId: String,
        model: String,
        embedding: FloatArray,
    ) = withConnection { it.upsertEmbedding(factId, embedding, model) }

    override suspend fun searchFacts(
        scopes: List<MemoryScope>,
        model: String,
        queryEmbedding: FloatArray,
        limit: Int,
    ): List<MemoryFactSearchHit> {
        if (scopes.isEmpty()) return emptyList()
        val rows = withConnection { connection ->
            val scopeClause = scopes.joinToString(" or ") { "(f.scope_type = ? and f.scope_id = ?)" }
            connection.prepareStatement(
                """
                select
                    f.*,
                    e.embedding_blob as embedding_blob
                from memory_facts f
                join memory_fact_embeddings e on e.fact_id = f.id
                where f.status = ?
                  and e.embedding_model = ?
                  and ($scopeClause)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, MemoryFactStatus.ACTIVE.name)
                statement.setString(2, model)
                var index = 3
                scopes.forEach { scope ->
                    statement.setString(index++, scope.type)
                    statement.setString(index++, scope.id)
                }
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                FactEmbeddingRow(
                                    fact = rs.toFact(),
                                    embedding = rs.getBytes("embedding_blob")?.toFloatArray() ?: FloatArray(0),
                                )
                            )
                        }
                    }
                }
            }
        }

        return rows
            .map { row ->
                MemoryFactSearchHit(
                    fact = row.fact,
                    score = cosineSimilarity(queryEmbedding, row.embedding),
                )
            }
            .sortedByDescending(MemoryFactSearchHit::score)
            .take(limit)
    }

    private suspend fun updateStatus(
        factId: String,
        status: MemoryFactStatus,
    ) {
        withConnection { connection ->
            connection.prepareStatement(
                """
                update memory_facts
                set status = ?, updated_at = ?
                where id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, status.name)
                statement.setString(2, Instant.now().toString())
                statement.setString(3, factId)
                statement.executeUpdate()
            }
        }
    }

    private suspend fun <T> withConnection(block: (Connection) -> T): T = withContext(ioDispatcher) {
        ensureInitialized()
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA foreign_keys = ON;")
                statement.execute("PRAGMA journal_mode = WAL;")
                statement.execute("PRAGMA busy_timeout = 5000;")
            }
            block(connection)
        }
    }

    private suspend fun ensureInitialized() {
        initMutex.withLock {
            if (initialized) return
            Class.forName("org.sqlite.JDBC")
            Files.createDirectories(dbPath.parent)
            DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("PRAGMA journal_mode = WAL;")
                }
                connection.inTransaction { ensureCurrentSchema(this) }
            }
            initialized = true
        }
    }

    private fun ensureCurrentSchema(connection: Connection) {
        MIGRATION_V1.forEach { sql ->
            connection.createStatement().use { statement -> statement.execute(sql) }
        }
        connection.createStatement().use { statement ->
            statement.execute("pragma user_version = 1")
        }
    }

    private fun ResultSet.toFactOrNull(): MemoryFact? = if (next()) toFact() else null

    private fun ResultSet.toFact(): MemoryFact =
        MemoryFact(
            id = getString("id"),
            scope = MemoryScope(
                type = getString("scope_type"),
                id = getString("scope_id"),
            ),
            kind = MemoryFactKind.valueOf(getString("kind")),
            title = getString("title"),
            body = getString("body"),
            slotKey = getString("slot_key"),
            status = MemoryFactStatus.valueOf(getString("status")),
            confidence = getFloat("confidence"),
            pinned = getInt("pinned") != 0,
            createdBy = getString("created_by"),
            createdAt = Instant.parse(getString("created_at")),
            updatedAt = Instant.parse(getString("updated_at")),
            supersedesFactId = getString("supersedes_fact_id"),
        )

    private fun bindParams(
        statement: PreparedStatement,
        params: List<Any>,
    ) {
        params.forEachIndexed { index, value ->
            when (value) {
                is String -> statement.setString(index + 1, value)
                is Int -> statement.setInt(index + 1, value)
                else -> error("Unsupported SQLite param type: ${value::class}")
            }
        }
    }

    private fun placeholders(size: Int): String = List(size) { "?" }.joinToString(", ")

    private inline fun <T> Connection.inTransaction(block: Connection.() -> T): T {
        autoCommit = false
        return try {
            block().also { commit() }
        } catch (error: Exception) {
            rollback()
            throw error
        }
    }

    private fun Connection.deleteSourceEventIfUnused(sourceEventId: String) {
        prepareStatement(
            """
            delete from memory_source_events
            where id = ?
              and not exists (
                  select 1 from memory_fact_evidence where source_event_id = ?
              )
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, sourceEventId)
            statement.setString(2, sourceEventId)
            statement.executeUpdate()
        }
    }

    private fun Connection.upsertEmbedding(
        factId: String,
        embedding: FloatArray?,
        model: String?,
    ) {
        if (embedding == null || model == null) return
        if (embedding.isEmpty()) error("Cannot save empty embedding (zero dimension)")
        prepareStatement(
            """
            insert into memory_fact_embeddings(
                fact_id, embedding_model, embedding_blob, dimension, updated_at
            ) values (?, ?, ?, ?, ?)
            on conflict(fact_id) do update set
                embedding_model = excluded.embedding_model,
                embedding_blob = excluded.embedding_blob,
                dimension = excluded.dimension,
                updated_at = excluded.updated_at
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, factId)
            statement.setString(2, model)
            statement.setBytes(3, embedding.toBlob())
            statement.setInt(4, embedding.size)
            statement.setString(5, Instant.now().toString())
            statement.executeUpdate()
        }
    }

    private data class FactEmbeddingRow(
        val fact: MemoryFact,
        val embedding: FloatArray,
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
    }
}
