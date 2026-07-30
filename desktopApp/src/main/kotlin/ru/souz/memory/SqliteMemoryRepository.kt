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
        val scope = input.scope.normalized()
        connection.prepareStatement(
            """
            insert into memory_source_events(
                id, owner_id, scope_type, scope_id, source_type, source_ref, text, metadata_json, created_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, id)
            statement.setString(2, input.ownerId.value)
            statement.setString(3, scope.type)
            statement.setString(4, scope.id)
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
                retireActiveCanonicalConflicts(input.ownerId, scope, canonicalKey)
            }

            prepareStatement(
                """
                insert into memory_facts(
                    id, owner_id, scope_type, scope_id, kind, title, body, slot_key, canonical_key, status,
                    retention, confidence, importance, pinned, created_by, content_hash, created_at, updated_at,
                    supersedes_fact_id
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                statement.setString(11, input.retention.name)
                statement.setFloat(12, input.confidence)
                statement.setFloat(13, input.importance)
                statement.setInt(14, if (input.pinned) 1 else 0)
                statement.setString(15, input.createdBy)
                statement.setString(16, input.contentHash)
                statement.setString(17, input.createdAt.toString())
                statement.setString(18, input.updatedAt.toString())
                statement.setString(19, input.supersedesFactId)
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
            enqueueDreamerRegionIfNeeded(
                factId = id,
                ownerId = input.ownerId,
                scope = scope,
                status = input.status,
                retention = input.retention,
                createdBy = input.createdBy,
                dirtyAt = input.updatedAt,
            )
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
        filter.scope?.compatibilityScopes()?.distinct()?.let { scopes ->
            clauses += scopes.joinToString(" or ", prefix = "(", postfix = ")") {
                "(scope_type = ? and scope_id = ?)"
            }
            scopes.forEach { scope ->
                params.add(scope.type)
                params.add(scope.id)
            }
        }
        filter.pinned?.let { pinned ->
            clauses += "pinned = ?"
            params.add(if (pinned) 1 else 0)
        }
        filter.query?.trim()?.takeIf(String::isNotBlank)?.let { query ->
            clauses += "(title like ? escape '!' or body like ? escape '!')"
            val like = literalLikePattern(query)
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
            val scope = fact.scope.normalized()
            val canonicalKey = controlledCanonicalKey(fact.canonicalKey)
            if (canonicalKey != null && fact.status == MemoryFactStatus.ACTIVE) {
                retireActiveCanonicalConflicts(fact.ownerId, scope, canonicalKey, exceptFactId = fact.id)
            }
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
                    retention = ?,
                    confidence = ?,
                    importance = ?,
                    pinned = ?,
                    content_hash = ?,
                    updated_at = ?,
                    supersedes_fact_id = ?
                where id = ? and updated_at = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, fact.ownerId.value)
                statement.setString(2, scope.type)
                statement.setString(3, scope.id)
                statement.setString(4, fact.kind.name)
                statement.setString(5, fact.title)
                statement.setString(6, fact.body)
                statement.setString(7, canonicalKey)
                statement.setString(8, canonicalKey)
                statement.setString(9, fact.retention.name)
                statement.setFloat(10, fact.confidence)
                statement.setFloat(11, fact.importance)
                statement.setInt(12, if (fact.pinned) 1 else 0)
                statement.setString(13, fact.contentHash)
                statement.setString(14, fact.updatedAt.toString())
                statement.setString(15, fact.supersedesFactId)
                statement.setString(16, fact.id)
                statement.setString(17, expectedUpdatedAt.toString())
                statement.executeUpdate()
            }
            if (updatedRows == 0) {
                error("Memory fact was modified concurrently: ${fact.id}")
            }
            upsertEmbedding(fact.id, embedding, embeddingModel, fact.contentHash)
            enqueueDreamerRegionIfNeeded(
                factId = fact.id,
                ownerId = fact.ownerId,
                scope = scope,
                status = fact.status,
                retention = fact.retention,
                createdBy = fact.createdBy,
                dirtyAt = fact.updatedAt,
            )
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
        val scopes = scope.compatibilityScopes().distinct()
        val scopeClause = scopes.joinToString(" or ") { "(scope_type = ? and scope_id = ?)" }
        val factIds = connection.prepareStatement(
            """
            select id from memory_facts
            where owner_id = ? and ($scopeClause)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, ownerId.value)
            scopes.forEachIndexed { index, candidateScope ->
                val offset = 2 + index * 2
                statement.setString(offset, candidateScope.type)
                statement.setString(offset + 1, candidateScope.id)
            }
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

    override suspend fun recordRetrieval(factIds: List<String>) {
        if (factIds.isEmpty()) return
        withConnection { connection ->
            val distinctFactIds = factIds.distinct()
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
                distinctFactIds.forEach { factId ->
                    statement.setString(1, factId)
                    statement.setString(2, now)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
            connection.enqueueDreamerRegionsForRetrievedFacts(distinctFactIds, Instant.now())
        }
    }

    override suspend fun createTombstone(
        ownerId: MemoryOwnerId,
        scope: MemoryScope,
        canonicalKey: String?,
        subjectKey: String?,
        reason: String,
    ) = withConnection { connection ->
        val normalizedScope = scope.normalized()
        connection.prepareStatement(
            """
            insert into memory_tombstones(id, owner_id, scope_type, scope_id, canonical_key, subject_key, reason, created_at)
            values (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, UUID.randomUUID().toString())
            statement.setString(2, ownerId.value)
            statement.setString(3, normalizedScope.type)
            statement.setString(4, normalizedScope.id)
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
            val candidateScopes = scopes.flatMap(MemoryScope::compatibilityScopes).distinct()
            if (candidateScopes.isEmpty()) return@withConnection false
            val scopeClause = candidateScopes.joinToString(" or ") { "(scope_type = ? and scope_id = ?)" }
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
                candidateScopes.forEach { scope ->
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

    override suspend fun findActiveFactByCanonicalKey(
        ownerId: MemoryOwnerId,
        scope: MemoryScope,
        canonicalKey: String,
    ): MemoryFact? = withConnection { connection ->
        val scopes = scope.compatibilityScopes().distinct()
        val scopeClause = scopes.joinToString(" or ") { "(scope_type = ? and scope_id = ?)" }
        connection.prepareStatement(
            """
            select * from memory_facts
            where owner_id = ?
              and ($scopeClause)
              and canonical_key = ?
              and status = ?
            order by updated_at desc
            limit 1
            """.trimIndent()
        ).use { statement ->
            var index = 1
            statement.setString(index++, ownerId.value)
            scopes.forEach { candidateScope ->
                statement.setString(index++, candidateScope.type)
                statement.setString(index++, candidateScope.id)
            }
            statement.setString(index++, canonicalKey)
            statement.setString(index, MemoryFactStatus.ACTIVE.name)
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
            val candidateScopes = scopes.compatibilityScopes()
            val scopeClause = candidateScopes.joinToString(" or ") { "(scope_type = ? and scope_id = ?)" }
            val likeClause = terms.joinToString(" or ") {
                "(lower(title) like ? escape '!' or lower(body) like ? escape '!' or lower(coalesce(canonical_key, '')) like ? escape '!')"
            }
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
                candidateScopes.forEach { scope ->
                    statement.setString(index++, scope.type)
                    statement.setString(index++, scope.id)
                }
                terms.forEach { term ->
                    val like = literalLikePattern(term)
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
            val candidateScopes = scopes.compatibilityScopes()
            val scopeClause = candidateScopes.joinToString(" or ") { "(f.scope_type = ? and f.scope_id = ?)" }
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
                candidateScopes.forEach { scope ->
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
                connection.inTransaction {
                    SqliteMemorySchema(legacyOwnerMigrationTarget).ensureCurrentSchema(this)
                }
            }
            initialized = true
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
            retention = enumValueOrDefault(getStringOrDefault("retention", MemoryRetention.DURABLE.name), MemoryRetention.DURABLE),
            confidence = getFloat("confidence"),
            importance = getFloatOrDefault("importance", getFloat("confidence")),
            pinned = getInt("pinned") != 0,
            createdBy = getString("created_by"),
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

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, default: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: default

    private fun controlledCanonicalKey(raw: String?): String? =
        normalizeCanonicalKey(raw)

    private fun Connection.retireActiveCanonicalConflicts(
        ownerId: MemoryOwnerId,
        scope: MemoryScope,
        canonicalKey: String,
        exceptFactId: String? = null,
    ) {
        val scopes = scope.compatibilityScopes().distinct()
        val scopeClause = scopes.joinToString(" or ") { "(scope_type = ? and scope_id = ?)" }
        prepareStatement(
            """
            update memory_facts
            set status = ?, updated_at = ?
            where owner_id = ?
              and ($scopeClause)
              and canonical_key = ?
              and status = ?
              and (? is null or id <> ?)
            """.trimIndent()
        ).use { statement ->
            var index = 1
            statement.setString(index++, MemoryFactStatus.RETIRED.name)
            statement.setString(index++, Instant.now().toString())
            statement.setString(index++, ownerId.value)
            scopes.forEach { candidateScope ->
                statement.setString(index++, candidateScope.type)
                statement.setString(index++, candidateScope.id)
            }
            statement.setString(index++, canonicalKey)
            statement.setString(index++, MemoryFactStatus.ACTIVE.name)
            statement.setString(index++, exceptFactId)
            statement.setString(index, exceptFactId)
            statement.executeUpdate()
        }
    }

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

    private fun literalLikePattern(value: String): String = buildString(value.length + 2) {
        append('%')
        value.forEach { char ->
            if (char == '%' || char == '_' || char == '!') append('!')
            append(char)
        }
        append('%')
    }

    private fun List<MemoryScope>.compatibilityScopes(): List<MemoryScope> =
        flatMap(MemoryScope::compatibilityScopes).distinct()

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
            "delete from memory_fact_supersedes where replacement_fact_id = ? or superseded_fact_id = ?"
        ).use { statement ->
            statement.setString(1, factId)
            statement.setString(2, factId)
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
}
