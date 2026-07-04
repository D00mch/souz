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

internal suspend fun initializeSqliteMemoryRepository(
    dbPath: Path,
    legacyOwnerMigrationTarget: MemoryOwnerId? = null,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    SqliteMemoryRepository(dbPath, legacyOwnerMigrationTarget, ioDispatcher).initialize()
}

class SqliteMemoryRepository(
    private val dbPath: Path,
    private val legacyOwnerMigrationTarget: MemoryOwnerId? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MemoryRepository {
    private val initMutex = Mutex()
    private var initialized = false

    internal suspend fun initialize() {
        ensureInitialized()
    }

    override suspend fun insertSourceEvent(input: NewMemorySourceEvent): String = withConnection { connection ->
        val id = UUID.randomUUID().toString()
        connection.prepareStatement(
            """
            insert into memory_source_events(
                id, owner_id, scope_type, scope_id, source_type, source_ref, text, metadata_json, created_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, id)
            statement.setString(2, input.ownerId.value)
            statement.setString(3, input.scope.type)
            statement.setString(4, input.scope.id)
            statement.setString(5, input.sourceType)
            statement.setString(6, input.sourceRef)
            statement.setString(7, input.text)
            statement.setString(8, input.metadataJson)
            statement.setString(9, input.createdAt.toString())
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
            val scope = input.scope.normalized()
            if (input.createdBy == "writer" && isScopeClosedForCapture(input.ownerId, scope)) {
                throw MemoryScopeClosedForCaptureException(input.ownerId, scope)
            }
            val canonicalKey = controlledCanonicalKey(input.canonicalKey)
            if (canonicalKey != null && input.status == MemoryFactStatus.ACTIVE) {
                prepareStatement(
                    """
                    update memory_facts
                    set status = ?, updated_at = ?
                    where owner_id = ? and scope_type = ? and scope_id = ? and canonical_key = ? and status = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, MemoryFactStatus.RETIRED.name)
                    statement.setString(2, Instant.now().toString())
                    statement.setString(3, input.ownerId.value)
                    statement.setString(4, scope.type)
                    statement.setString(5, scope.id)
                    statement.setString(6, canonicalKey)
                    statement.setString(7, MemoryFactStatus.ACTIVE.name)
                    statement.executeUpdate()
                }
            }

            prepareStatement(
                """
                insert into memory_facts(
                    id, owner_id, scope_type, scope_id, kind, title, body, slot_key, canonical_key, status,
                    validity, retention, sensitivity, confidence, importance, pinned, created_by, version,
                    content_hash, created_at, updated_at, last_observed_at, supersedes_fact_id
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, id)
                statement.setString(2, input.ownerId.value)
                statement.setString(3, scope.type)
                statement.setString(4, scope.id)
                statement.setString(5, input.kind.name)
                statement.setString(6, input.title)
                statement.setString(7, input.body)
                statement.setString(8, canonicalKey)
                statement.setString(9, canonicalKey)
                statement.setString(10, input.status.name)
                statement.setString(11, input.validity.name)
                statement.setString(12, input.retention.name)
                statement.setString(13, input.sensitivity.name)
                statement.setFloat(14, input.confidence)
                statement.setFloat(15, input.importance)
                statement.setInt(16, if (input.pinned) 1 else 0)
                statement.setString(17, input.createdBy)
                statement.setLong(18, input.version)
                statement.setString(19, input.contentHash)
                statement.setString(20, input.createdAt.toString())
                statement.setString(21, input.updatedAt.toString())
                statement.setString(22, input.lastObservedAt.toString())
                statement.setString(23, input.supersedesFactId)
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
            upsertEmbedding(id, embedding, embeddingModel, input.contentHash)
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
                se.owner_id as source_owner_id,
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
                                    ownerId = MemoryOwnerId(rs.getString("source_owner_id") ?: LEGACY_OWNER_ID),
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
        filter.ownerId?.let { ownerId ->
            clauses += "owner_id = ?"
            params.add(ownerId.value)
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
        filter.pinned?.let { pinned ->
            clauses += "pinned = ?"
            params.add(if (pinned) 1 else 0)
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
                set owner_id = ?,
                    scope_type = ?,
                    scope_id = ?,
                    kind = ?,
                    title = ?,
                    body = ?,
                    slot_key = ?,
                    canonical_key = ?,
                    validity = ?,
                    retention = ?,
                    sensitivity = ?,
                    confidence = ?,
                    importance = ?,
                    pinned = ?,
                    version = ?,
                    content_hash = ?,
                    last_observed_at = ?,
                    updated_at = ?
                where id = ? and updated_at = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, fact.ownerId.value)
                statement.setString(2, fact.scope.type)
                statement.setString(3, fact.scope.id)
                statement.setString(4, fact.kind.name)
                statement.setString(5, fact.title)
                statement.setString(6, fact.body)
                statement.setString(7, fact.canonicalKey)
                statement.setString(8, fact.canonicalKey)
                statement.setString(9, fact.validity.name)
                statement.setString(10, fact.retention.name)
                statement.setString(11, fact.sensitivity.name)
                statement.setFloat(12, fact.confidence)
                statement.setFloat(13, fact.importance)
                statement.setInt(14, if (fact.pinned) 1 else 0)
                statement.setLong(15, fact.version)
                statement.setString(16, fact.contentHash)
                statement.setString(17, fact.lastObservedAt.toString())
                statement.setString(18, fact.updatedAt.toString())
                statement.setString(19, fact.id)
                statement.setString(20, expectedUpdatedAt.toString())
                statement.executeUpdate()
            }
            if (updatedRows == 0) {
                error("Memory fact was modified concurrently: ${fact.id}")
            }
            upsertEmbedding(fact.id, embedding, embeddingModel, fact.contentHash)
        }
        fact
    }

    override suspend fun retireFact(factId: String) {
        updateStatus(factId, MemoryFactStatus.RETIRED)
    }

    override suspend fun deleteFact(factId: String): Unit = withConnection { connection ->
        connection.inTransaction {
            deleteFactById(factId)
        }
    }

    override suspend fun deleteFactsByScope(ownerId: MemoryOwnerId, scope: MemoryScope): Unit = withConnection { connection ->
        val normalized = scope.normalized()
        val factIds = connection.prepareStatement(
            """
            select id from memory_facts
            where owner_id = ? and scope_type = ? and scope_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, ownerId.value)
            statement.setString(2, normalized.type)
            statement.setString(3, normalized.id)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(rs.getString("id"))
                }
            }
        }
        connection.inTransaction {
            factIds.forEach { factId -> deleteFactById(factId) }
        }
    }

    override suspend fun deleteSourceEventIfUnused(sourceEventId: String) =
        withConnection { it.deleteSourceEventIfUnused(sourceEventId) }

    override suspend fun getFactsWithoutEmbedding(
        scopes: List<MemoryScope>,
        model: String,
        expectedDimension: Int?,
        limit: Int,
    ): List<MemoryFact> {
        if (scopes.isEmpty()) return emptyList()
        return withConnection { connection ->
            val scopeClause = scopes.joinToString(" or ") { "(f.scope_type = ? and f.scope_id = ?)" }
            val staleClause = "e.fact_id is null or e.content_hash is null or e.content_hash != f.content_hash"
            val dimensionClause = if (expectedDimension != null) {
                "and ($staleClause or e.dimension != ?)"
            } else {
                "and ($staleClause)"
            }
            connection.prepareStatement(
                """
                select f.* from memory_facts f
                left join memory_fact_embeddings e on e.fact_id = f.id and e.embedding_model = ?
                where f.status = ?
                  $dimensionClause
                  and ($scopeClause)
                limit ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, model)
                statement.setString(2, MemoryFactStatus.ACTIVE.name)
                var index = 3
                expectedDimension?.let { statement.setInt(index++, it) }
                scopes.forEach { scope ->
                    statement.setString(index++, scope.type)
                    statement.setString(index++, scope.id)
                }
                statement.setInt(index++, limit)
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

    override suspend fun enqueueEmbeddingJob(
        factId: String,
        ownerId: MemoryOwnerId,
        model: String,
        contentHash: String,
    ) = withConnection { connection ->
        connection.prepareStatement(
            """
            insert into memory_index_jobs(
                fact_id, owner_id, embedding_model, content_hash, status, attempt_count,
                next_attempt_at, created_at, updated_at
            ) values (?, ?, ?, ?, 'PENDING', 0, ?, ?, ?)
            on conflict(fact_id, embedding_model, content_hash) do update set
                status = case when status = 'COMPLETED' then status else 'PENDING' end,
                updated_at = excluded.updated_at
            """.trimIndent()
        ).use { statement ->
            val now = Instant.now().toString()
            statement.setString(1, factId)
            statement.setString(2, ownerId.value)
            statement.setString(3, model)
            statement.setString(4, contentHash)
            statement.setString(5, now)
            statement.setString(6, now)
            statement.setString(7, now)
            statement.executeUpdate()
            Unit
        }
    }

    override suspend fun markEmbeddingJobCompleted(
        factId: String,
        model: String,
        contentHash: String,
    ) = updateIndexJob(factId, model, contentHash, "COMPLETED", null)

    override suspend fun markEmbeddingJobFailed(
        factId: String,
        model: String,
        contentHash: String,
        errorCode: String,
    ) = updateIndexJob(factId, model, contentHash, "FAILED", errorCode.take(80))

    private suspend fun updateIndexJob(
        factId: String,
        model: String,
        contentHash: String,
        status: String,
        errorCode: String?,
    ) = withConnection { connection ->
        connection.prepareStatement(
            """
            update memory_index_jobs
            set status = ?,
                attempt_count = attempt_count + case when ? = 'FAILED' then 1 else 0 end,
                last_error_code = ?,
                updated_at = ?
            where fact_id = ? and embedding_model = ? and content_hash = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, status)
            statement.setString(2, status)
            statement.setString(3, errorCode)
            statement.setString(4, Instant.now().toString())
            statement.setString(5, factId)
            statement.setString(6, model)
            statement.setString(7, contentHash)
            statement.executeUpdate()
            Unit
        }
    }

    override suspend fun recordOperation(
        factId: String?,
        ownerId: MemoryOwnerId,
        type: MemoryOperationType,
        reason: String,
    ) = withConnection { connection ->
        connection.prepareStatement(
            """
            insert into memory_operation_log(id, fact_id, owner_id, type, reason, created_at)
            values (?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, UUID.randomUUID().toString())
            statement.setString(2, factId)
            statement.setString(3, ownerId.value)
            statement.setString(4, type.name)
            statement.setString(5, reason.take(120))
            statement.setString(6, Instant.now().toString())
            statement.executeUpdate()
            Unit
        }
    }

    override suspend fun recordRetrieval(factIds: List<String>) {
        if (factIds.isEmpty()) return
        withConnection { connection ->
            connection.prepareStatement(
                """
                insert into memory_fact_stats(fact_id, last_retrieved_at, retrieval_count)
                values (?, ?, 1)
                on conflict(fact_id) do update set
                    last_retrieved_at = excluded.last_retrieved_at,
                    retrieval_count = retrieval_count + 1
                """.trimIndent()
            ).use { statement ->
                val now = Instant.now().toString()
                factIds.distinct().forEach { factId ->
                    statement.setString(1, factId)
                    statement.setString(2, now)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }

    override suspend fun createTombstone(
        ownerId: MemoryOwnerId,
        scope: MemoryScope,
        canonicalKey: String?,
        subjectKey: String?,
        reason: String,
    ) = withConnection { connection ->
        connection.prepareStatement(
            """
            insert into memory_tombstones(id, owner_id, scope_type, scope_id, canonical_key, subject_key, reason, created_at)
            values (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, UUID.randomUUID().toString())
            statement.setString(2, ownerId.value)
            statement.setString(3, scope.type)
            statement.setString(4, scope.id)
            statement.setString(5, canonicalKey)
            statement.setString(6, subjectKey)
            statement.setString(7, reason.take(80))
            statement.setString(8, Instant.now().toString())
            statement.executeUpdate()
            Unit
        }
    }

    override suspend fun hasTombstone(
        ownerId: MemoryOwnerId,
        scopes: List<MemoryScope>,
        canonicalKey: String?,
        subjectKey: String?,
    ): Boolean {
        if (canonicalKey == null && subjectKey == null) return false
        return withConnection { connection ->
            val scopeClause = scopes.joinToString(" or ") { "(scope_type = ? and scope_id = ?)" }
            connection.prepareStatement(
                """
                select 1 from memory_tombstones
                where owner_id = ?
                  and ($scopeClause)
                  and (
                    (? is not null and canonical_key = ?)
                    or (? is not null and subject_key = ?)
                  )
                limit 1
                """.trimIndent()
            ).use { statement ->
                var index = 1
                statement.setString(index++, ownerId.value)
                scopes.forEach { scope ->
                    statement.setString(index++, scope.type)
                    statement.setString(index++, scope.id)
                }
                statement.setString(index++, canonicalKey)
                statement.setString(index++, canonicalKey)
                statement.setString(index++, subjectKey)
                statement.setString(index, subjectKey)
                statement.executeQuery().use { it.next() }
            }
        }
    }

    override suspend fun findActiveFactBySlotKey(
        scope: MemoryScope,
        slotKey: String,
    ): MemoryFact? = findActiveFactByCanonicalKey(MemoryOwnerId(LEGACY_OWNER_ID), scope, slotKey)

    override suspend fun findActiveFactByCanonicalKey(
        ownerId: MemoryOwnerId,
        scope: MemoryScope,
        canonicalKey: String,
    ): MemoryFact? = withConnection { connection ->
        connection.prepareStatement(
            """
            select * from memory_facts
            where owner_id = ?
              and scope_type = ?
              and scope_id = ?
              and canonical_key = ?
              and status = ?
            order by updated_at desc
            limit 1
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, ownerId.value)
            statement.setString(2, scope.type)
            statement.setString(3, scope.id)
            statement.setString(4, canonicalKey)
            statement.setString(5, MemoryFactStatus.ACTIVE.name)
            statement.executeQuery().use { rs -> rs.toFactOrNull() }
        }
    }

    override suspend fun lexicalSearchFacts(
        ownerId: MemoryOwnerId,
        scopes: List<MemoryScope>,
        query: String,
        limit: Int,
    ): List<MemoryFactSearchHit> {
        if (scopes.isEmpty() || query.isBlank()) return emptyList()
        val terms = query.lowercase().split(Regex("""[^\p{L}\p{N}_./:-]+""")).filter { it.length >= 2 }.take(8)
        if (terms.isEmpty()) return emptyList()
        return withConnection { connection ->
            val scopeClause = scopes.joinToString(" or ") { "(scope_type = ? and scope_id = ?)" }
            val likeClause = terms.joinToString(" or ") { "(lower(title) like ? or lower(body) like ? or lower(coalesce(canonical_key, '')) like ?)" }
            connection.prepareStatement(
                """
                select * from memory_facts
                where owner_id = ?
                  and status = ?
                  and ($scopeClause)
                  and ($likeClause)
                order by updated_at desc
                limit ?
                """.trimIndent()
            ).use { statement ->
                var index = 1
                statement.setString(index++, ownerId.value)
                statement.setString(index++, MemoryFactStatus.ACTIVE.name)
                scopes.forEach { scope ->
                    statement.setString(index++, scope.type)
                    statement.setString(index++, scope.id)
                }
                terms.forEach { term ->
                    val like = "%$term%"
                    statement.setString(index++, like)
                    statement.setString(index++, like)
                    statement.setString(index++, like)
                }
                statement.setInt(index, limit)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            val fact = rs.toFact()
                            val score = terms.count { term ->
                                "${fact.title} ${fact.body} ${fact.canonicalKey.orEmpty()}".lowercase().contains(term)
                            }.toFloat() / terms.size
                            add(MemoryFactSearchHit(fact, score))
                        }
                    }.sortedByDescending(MemoryFactSearchHit::score)
                }
            }
        }
    }

    override suspend fun replaceEmbedding(
        factId: String,
        model: String,
        embedding: FloatArray,
        contentHash: String?,
    ) = withConnection { it.upsertEmbedding(factId, embedding, model, contentHash) }

    override suspend fun searchFacts(
        ownerId: MemoryOwnerId,
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
                  and f.owner_id = ?
                  and e.embedding_model = ?
                  and e.dimension = ?
                  and e.content_hash = f.content_hash
                  and ($scopeClause)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, MemoryFactStatus.ACTIVE.name)
                statement.setString(2, ownerId.value)
                statement.setString(3, model)
                statement.setInt(4, queryEmbedding.size)
                var index = 5
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
        adoptSingleExistingOwner(connection, legacyOwnerMigrationTarget)
        connection.createStatement().use { statement -> statement.execute("pragma user_version = 5") }
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
        OWNER_TABLES.forEach { table ->
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
        OWNER_TABLES.forEach { table ->
            connection.prepareStatement("update $table set owner_id = ? where owner_id = ?").use { statement ->
                statement.setString(1, target)
                statement.setString(2, existing)
                statement.executeUpdate()
            }
        }
    }

    private fun Connection.distinctOwnerIds(): Set<String> {
        val unionSql = OWNER_TABLES.joinToString("\nunion\n") { table ->
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

    private fun ResultSet.toFactOrNull(): MemoryFact? = if (next()) toFact() else null

    private fun ResultSet.toFact(): MemoryFact =
        MemoryFact(
            id = getString("id"),
            ownerId = MemoryOwnerId(getStringOrDefault("owner_id", LEGACY_OWNER_ID)),
            scope = MemoryScope(
                type = getString("scope_type"),
                id = getString("scope_id"),
            ),
            kind = MemoryFactKind.valueOf(getString("kind")),
            title = getString("title"),
            body = getString("body"),
            slotKey = getNullableString("slot_key"),
            canonicalKey = getNullableString("canonical_key"),
            status = MemoryFactStatus.valueOf(getString("status")),
            validity = enumValueOrDefault(getStringOrDefault("validity", MemoryFactValidity.VALID.name), MemoryFactValidity.VALID),
            retention = enumValueOrDefault(getStringOrDefault("retention", MemoryRetention.DURABLE.name), MemoryRetention.DURABLE),
            sensitivity = enumValueOrDefault(getStringOrDefault("sensitivity", MemorySensitivity.NORMAL.name), MemorySensitivity.NORMAL),
            confidence = getFloat("confidence"),
            importance = getFloatOrDefault("importance", getFloat("confidence")),
            pinned = getInt("pinned") != 0,
            createdBy = getString("created_by"),
            version = getLongOrDefault("version", 1L),
            contentHash = getStringOrDefault(
                "content_hash",
                stableMemoryContentHash(
                    getString("title"),
                    getString("body"),
                    MemoryFactKind.valueOf(getString("kind")),
                    getNullableString("canonical_key") ?: getNullableString("slot_key"),
                ),
            ),
            createdAt = Instant.parse(getString("created_at")),
            updatedAt = Instant.parse(getString("updated_at")),
            lastObservedAt = Instant.parse(getStringOrDefault("last_observed_at", getString("updated_at"))),
            supersedesFactId = getNullableString("supersedes_fact_id"),
        )

    private fun ResultSet.hasColumn(name: String): Boolean {
        val meta = metaData
        for (index in 1..meta.columnCount) {
            if (meta.getColumnLabel(index).equals(name, ignoreCase = true)) return true
        }
        return false
    }

    private fun ResultSet.getNullableString(name: String): String? =
        if (hasColumn(name)) getString(name) else null

    private fun ResultSet.getStringOrDefault(name: String, default: String): String =
        getNullableString(name) ?: default

    private fun ResultSet.getFloatOrDefault(name: String, default: Float): Float =
        if (hasColumn(name)) getFloat(name) else default

    private fun ResultSet.getLongOrDefault(name: String, default: Long): Long =
        if (hasColumn(name)) getLong(name) else default

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, default: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: default

    private fun controlledCanonicalKey(raw: String?): String? =
        normalizeCanonicalKey(raw)

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

    private fun Connection.deleteFactById(factId: String) {
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
            "delete from memory_fact_stats where fact_id = ?"
        ).use { statement ->
            statement.setString(1, factId)
            statement.executeUpdate()
        }

        prepareStatement(
            "delete from memory_index_jobs where fact_id = ?"
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

    private fun Connection.upsertEmbedding(
        factId: String,
        embedding: FloatArray?,
        model: String?,
        contentHash: String?,
    ) {
        if (embedding == null || model == null) return
        if (embedding.isEmpty()) error("Cannot save empty embedding (zero dimension)")
        val embeddingContentHash = contentHash ?: currentFactContentHash(factId) ?: return
        prepareStatement(
            """
            insert into memory_fact_embeddings(
                fact_id, embedding_model, embedding_blob, dimension, content_hash, updated_at
            ) values (?, ?, ?, ?, ?, ?)
            on conflict(fact_id) do update set
                embedding_model = excluded.embedding_model,
                embedding_blob = excluded.embedding_blob,
                dimension = excluded.dimension,
                content_hash = excluded.content_hash,
                updated_at = excluded.updated_at
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, factId)
            statement.setString(2, model)
            statement.setBytes(3, embedding.toBlob())
            statement.setInt(4, embedding.size)
            statement.setString(5, embeddingContentHash)
            statement.setString(6, Instant.now().toString())
            statement.executeUpdate()
        }
    }

    private fun Connection.currentFactContentHash(factId: String): String? =
        prepareStatement("select content_hash from memory_facts where id = ?").use { statement ->
            statement.setString(1, factId)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.getString("content_hash") else null
            }
        }

    private fun Connection.isScopeClosedForCapture(ownerId: MemoryOwnerId, scope: MemoryScope): Boolean =
        prepareStatement(
            """
            select 1 from memory_tombstones
            where owner_id = ?
              and scope_type = ?
              and scope_id = ?
              and subject_key = ?
            limit 1
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, ownerId.value)
            statement.setString(2, scope.type)
            statement.setString(3, scope.id)
            statement.setString(4, MEMORY_SCOPE_CLOSED_SUBJECT_KEY)
            statement.executeQuery().use { it.next() }
        }

    private data class FactEmbeddingRow(
        val fact: MemoryFact,
        val embedding: FloatArray,
    )

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
            """
            create table if not exists memory_budget_reservations (
                request_id text primary key,
                period_key text not null,
                estimated_input_tokens integer not null,
                reserved_output_tokens integer not null,
                actual_input_tokens integer,
                actual_output_tokens integer,
                call_count integer not null default 1,
                status text not null,
                is_estimated integer not null default 0,
                created_at text not null,
                updated_at text not null
            )
            """.trimIndent(),
            "create index if not exists memory_budget_period_idx on memory_budget_reservations(period_key, status)",
        )
        private val MIGRATION_V4 = listOf(
            "drop index if exists memory_active_slot_unique",
        )
        private val OWNER_TABLES = listOf(
            "memory_source_events",
            "memory_facts",
            "memory_index_jobs",
            "memory_operation_log",
            "memory_tombstones",
            "memory_maintenance_jobs",
        )
    }
}
