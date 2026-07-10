package ru.souz.memory

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

private data class MaintenanceJob(
    val clusterKey: String,
    val ownerId: String,
    val latestDirtyAt: String,
    val createdAt: String,
    val reasons: String,
    val attemptCount: Int,
)

class MemoryMaintenanceWorker(
    private val dbPath: Path,
    private val consolidator: MemoryConsolidator = NoopMemoryConsolidator,
    private val qualityGate: MemoryConsolidationQualityGate = DefaultMemoryConsolidationQualityGate,
    private val embeddingModel: String? = null,
) {
    suspend fun runOnce(preferences: MemoryMaintenancePreferences, ignoreBackoff: Boolean = false): Int {
        if (preferences.mode == MemoryMaintenanceMode.OFF) {
            return 0
        }
        initializeSqliteMemoryRepository(dbPath)
        val jobs = pendingJobs(MAX_CLUSTERS_PER_RUN, ignoreBackoff)
        if (jobs.isEmpty()) {
            return 0
        }
        var processed = 0
        jobs.forEach { job ->
            when {
                job.isLegacyChatMigration() -> {
                    processed += withConnection { connection ->
                        connection.inTransaction { processLegacyChatMigration(job, Instant.now().toString()) }
                    }
                }
                job.isDreamerRegionRewrite() -> {
                    processed += processDreamerRegionRewrite(job, preferences)
                }
                else -> {
                    withConnection { connection ->
                        connection.inTransaction { blockUnsupportedJob(job, Instant.now().toString()) }
                    }
                }
            }
        }
        return processed
    }

    private suspend fun pendingJobs(limit: Int, ignoreBackoff: Boolean): List<MaintenanceJob> = withConnection { connection ->
        val dueCondition = if (ignoreBackoff) "" else "and next_attempt_at <= ?"
        connection.prepareStatement(
            """
            select cluster_key, owner_id, latest_dirty_at, created_at, reasons, attempt_count
            from memory_maintenance_jobs
            where status = 'PENDING' $dueCondition
            order by priority desc, latest_dirty_at desc
            limit ?
            """.trimIndent()
        ).use { statement ->
            var index = 1
            if (!ignoreBackoff) statement.setString(index++, Instant.now().toString())
            statement.setInt(index, limit)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            MaintenanceJob(
                                clusterKey = rs.getString("cluster_key"),
                                ownerId = rs.getString("owner_id"),
                                latestDirtyAt = rs.getString("latest_dirty_at"),
                                createdAt = rs.getString("created_at"),
                                reasons = rs.getString("reasons").orEmpty(),
                                attemptCount = rs.getInt("attempt_count"),
                            )
                        )
                    }
                }
            }
        }
    }

    private suspend fun <T> withConnection(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use(block)
    }

    private suspend fun processDreamerRegionRewrite(
        job: MaintenanceJob,
        preferences: MemoryMaintenancePreferences,
    ): Int {
        val target = parseDreamerRegionMaintenanceClusterKey(job.clusterKey)
            ?: return blockJobResult(job)
        if (consolidator === NoopMemoryConsolidator) {
            return blockJobResult(job)
        }
        val ownerId = target.ownerId
        val scope = target.scope
        if (scope.type !in DREAMER_DURABLE_SCOPE_TYPES) {
            return blockJobResult(job)
        }
        val region = target.anchorFactId
            ?.let { anchorFactId -> loadDreamerNeighborhood(ownerId, scope, anchorFactId) }
            ?: loadDreamerRegion(ownerId, scope, job)
        if (region.size < 2) return completeWithoutReplacement(job)

        val input = MemoryConsolidationInput(
            ownerId = ownerId,
            scope = scope,
            facts = region,
            modelAlias = preferences.modelAlias,
        )
        val candidates = try {
            consolidator.consolidate(input).mapNotNull { it.normalizedCandidate() }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return deferJobResult(job)
        }
        if (candidates.isEmpty()) return completeWithoutReplacement(job)
        val evaluation = qualityGate.evaluate(input, candidates)
        if (!evaluation.accepted) return completeWithoutReplacement(job)
        val regionFactIds = region.map { it.fact.id }
        if (hasExternalCanonicalConflict(ownerId, scope, regionFactIds, candidates)) {
            return completeWithoutReplacement(job)
        }
        val evidenceTextBySourceEventId = region
            .flatMap { it.evidence }
            .mapNotNull { detail -> detail.evidence.evidenceText?.let { detail.sourceEvent.id to it } }
            .toMap()

        val inserted = withConnection { connection ->
            connection.inTransaction {
                commitDreamerReplacement(
                    job,
                    ownerId,
                    scope,
                    candidates,
                    evidenceTextBySourceEventId,
                    Instant.now().toString(),
                )
            }
        }
        return if (inserted > 0) 1 else 0
    }

    private suspend fun blockJobResult(job: MaintenanceJob): Int =
        withConnection { connection ->
            connection.inTransaction { blockUnsupportedJob(job, Instant.now().toString()) }
            0
        }

    private suspend fun deferJobResult(job: MaintenanceJob): Int =
        withConnection { connection ->
            connection.inTransaction {
                deferJob(job, Instant.now())
            }
            0
        }

    private suspend fun completeWithoutReplacement(job: MaintenanceJob): Int {
        withConnection { connection ->
            connection.inTransaction {
                completeJob(job, Instant.now().toString())
            }
        }
        return 0
    }

    private suspend fun hasExternalCanonicalConflict(
        ownerId: MemoryOwnerId,
        scope: MemoryScope,
        sourceFactIds: List<String>,
        candidates: List<MemoryConsolidationCandidate>,
    ): Boolean {
        val canonicalKeys = candidates.mapNotNull { it.canonicalKey }.distinct()
        if (canonicalKeys.isEmpty()) return false
        return withConnection { connection ->
            val keyPlaceholders = List(canonicalKeys.size) { "?" }.joinToString(", ")
            val sourcePlaceholders = List(sourceFactIds.size) { "?" }.joinToString(", ")
            connection.prepareStatement(
                """
                select 1 from memory_facts
                where owner_id = ?
                  and scope_type = ?
                  and scope_id = ?
                  and status = ?
                  and canonical_key in ($keyPlaceholders)
                  and id not in ($sourcePlaceholders)
                limit 1
                """.trimIndent()
            ).use { statement ->
                var index = 1
                statement.setString(index++, ownerId.value)
                statement.setString(index++, scope.type)
                statement.setString(index++, scope.id)
                statement.setString(index++, MemoryFactStatus.ACTIVE.name)
                canonicalKeys.forEach { statement.setString(index++, it) }
                sourceFactIds.forEach { statement.setString(index++, it) }
                statement.executeQuery().use { it.next() }
            }
        }
    }

    private suspend fun loadDreamerRegion(
        ownerId: MemoryOwnerId,
        scope: MemoryScope,
        job: MaintenanceJob,
    ): List<MemoryFactDetails> {
        val factIds = withConnection { connection ->
            connection.prepareStatement(
                """
                select f.id
                from memory_facts f
                left join memory_fact_stats s on s.fact_id = f.id
                where f.owner_id = ?
                  and f.scope_type = ?
                  and f.scope_id = ?
                  and f.status = ?
                  and f.retention = ?
                  and f.updated_at <= ?
                order by case
                           when f.updated_at >= ? then 2
                           when s.last_retrieved_at is not null then 1
                           else 0
                         end desc,
                         coalesce(s.last_retrieved_at, f.updated_at) desc,
                         f.importance desc,
                         f.pinned desc,
                         f.updated_at desc
                limit ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, ownerId.value)
                statement.setString(2, scope.type)
                statement.setString(3, scope.id)
                statement.setString(4, MemoryFactStatus.ACTIVE.name)
                statement.setString(5, MemoryRetention.DURABLE.name)
                statement.setString(6, job.latestDirtyAt)
                statement.setString(7, job.createdAt)
                statement.setInt(8, DREAMER_REGION_FACT_LIMIT)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(rs.getString("id"))
                    }
                }
            }
        }
        val repository = SqliteMemoryRepository(dbPath)
        return factIds.mapNotNull { factId -> repository.getFactDetails(factId) }
    }

    private suspend fun loadDreamerNeighborhood(
        ownerId: MemoryOwnerId,
        scope: MemoryScope,
        anchorFactId: String,
    ): List<MemoryFactDetails> {
        val repository = SqliteMemoryRepository(dbPath)
        val anchor = repository.getFactDetails(anchorFactId)
            ?.takeIf { details ->
                details.fact.ownerId == ownerId &&
                    details.fact.scope.normalized() == scope &&
                    details.fact.status == MemoryFactStatus.ACTIVE &&
                    details.fact.retention == MemoryRetention.DURABLE
            }
            ?: return emptyList()
        val nearestIds = embeddingModel
            ?.let { model -> loadNearestFactIds(ownerId, scope, anchor.fact, model) }
            .orEmpty()
        val fallbackIds = loadRecentFactIds(ownerId, scope, anchorFactId, NEIGHBORHOOD_FACT_LIMIT * 2)
        val neighborIds = (nearestIds + fallbackIds)
            .distinct()
            .filterNot { it == anchorFactId }
            .take(NEIGHBORHOOD_FACT_LIMIT - 1)
        return listOf(anchor) + neighborIds.mapNotNull { repository.getFactDetails(it) }
    }

    private suspend fun loadNearestFactIds(
        ownerId: MemoryOwnerId,
        scope: MemoryScope,
        anchor: MemoryFact,
        model: String,
    ): List<String> = withConnection { connection ->
        val anchorEmbedding = connection.prepareStatement(
            """
            select e.embedding_blob, e.dimension
            from memory_fact_embeddings e
            join memory_facts f on f.id = e.fact_id
            where f.id = ? and e.embedding_model = ? and e.content_hash = f.content_hash
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, anchor.id)
            statement.setString(2, model)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.getBytes("embedding_blob").toFloatArray() to rs.getInt("dimension") else null
            }
        } ?: return@withConnection emptyList()

        connection.prepareStatement(
            """
            select f.id, e.embedding_blob
            from memory_facts f
            join memory_fact_embeddings e on e.fact_id = f.id
            where f.id != ?
              and f.owner_id = ?
              and f.scope_type = ?
              and f.scope_id = ?
              and f.status = ?
              and f.retention = ?
              and e.embedding_model = ?
              and e.dimension = ?
              and e.content_hash = f.content_hash
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, anchor.id)
            statement.setString(2, ownerId.value)
            statement.setString(3, scope.type)
            statement.setString(4, scope.id)
            statement.setString(5, MemoryFactStatus.ACTIVE.name)
            statement.setString(6, MemoryRetention.DURABLE.name)
            statement.setString(7, model)
            statement.setInt(8, anchorEmbedding.second)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(rs.getString("id") to cosineSimilarity(anchorEmbedding.first, rs.getBytes("embedding_blob").toFloatArray()))
                    }
                }.sortedByDescending { it.second }
                    .take(NEIGHBORHOOD_FACT_LIMIT - 1)
                    .map { it.first }
            }
        }
    }

    private suspend fun loadRecentFactIds(
        ownerId: MemoryOwnerId,
        scope: MemoryScope,
        anchorFactId: String,
        limit: Int,
    ): List<String> = withConnection { connection ->
        connection.prepareStatement(
            """
            select id from memory_facts
            where id != ? and owner_id = ? and scope_type = ? and scope_id = ? and status = ? and retention = ?
            order by updated_at desc
            limit ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, anchorFactId)
            statement.setString(2, ownerId.value)
            statement.setString(3, scope.type)
            statement.setString(4, scope.id)
            statement.setString(5, MemoryFactStatus.ACTIVE.name)
            statement.setString(6, MemoryRetention.DURABLE.name)
            statement.setInt(7, limit)
            statement.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("id")) } }
        }
    }

    private fun MemoryConsolidationCandidate.normalizedCandidate(): MemoryConsolidationCandidate? {
        val title = MemorySanitizer.redact(title.trim()).takeIf(String::isNotBlank)
        val body = MemorySanitizer.redact(body.trim()).takeIf(String::isNotBlank)
        return if (title == null || body == null) {
            null
        } else {
            copy(
                title = title,
                body = body,
                canonicalKey = normalizeCanonicalKey(canonicalKey),
                confidence = confidence.coerceIn(0f, 1f),
                importance = importance.coerceIn(0f, 1f),
                sourceFactIds = sourceFactIds.distinct(),
                evidenceSourceEventIds = evidenceSourceEventIds.distinct(),
            )
        }
    }

    private fun Connection.commitDreamerReplacement(
        job: MaintenanceJob,
        ownerId: MemoryOwnerId,
        scope: MemoryScope,
        candidates: List<MemoryConsolidationCandidate>,
        evidenceTextBySourceEventId: Map<String, String>,
        now: String,
    ): Int {
        val replacedFactIds = candidates.flatMap { it.sourceFactIds }.distinct()
        val jobUpdated = completeJob(
            job = job,
            now = now,
        )
        if (jobUpdated == 0) return 0
        retireFacts(replacedFactIds, now)
        val insertedFacts = candidates.map { candidate ->
            insertDreamerFact(
                ownerId = ownerId,
                scope = scope,
                candidate = candidate,
                supersedesFactId = candidate.sourceFactIds.firstOrNull(),
                evidenceTextBySourceEventId = evidenceTextBySourceEventId,
                now = now,
            )
        }
        insertedFacts.zip(candidates).forEach { (fact, candidate) ->
            insertSupersedes(
                replacementFactId = fact.first,
                supersededFactIds = candidate.sourceFactIds,
                ownerId = ownerId,
                now = now,
            )
            enqueueEmbeddingJob(
                factId = fact.first,
                ownerId = ownerId,
                contentHash = fact.second,
                now = now,
            )
        }
        return insertedFacts.size
    }

    private fun Connection.retireFacts(factIds: List<String>, now: String) {
        if (factIds.isEmpty()) return
        prepareStatement(
            """
            update memory_facts
            set status = ?,
                updated_at = ?
            where id = ? and status = ?
            """.trimIndent()
        ).use { statement ->
            factIds.distinct().forEach { factId ->
                statement.setString(1, MemoryFactStatus.RETIRED.name)
                statement.setString(2, now)
                statement.setString(3, factId)
                statement.setString(4, MemoryFactStatus.ACTIVE.name)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun Connection.insertDreamerFact(
        ownerId: MemoryOwnerId,
        scope: MemoryScope,
        candidate: MemoryConsolidationCandidate,
        supersedesFactId: String?,
        evidenceTextBySourceEventId: Map<String, String>,
        now: String,
    ): Pair<String, String> {
        val factId = UUID.randomUUID().toString()
        val contentHash = stableMemoryContentHash(candidate.title, candidate.body, candidate.kind, candidate.canonicalKey)
        prepareStatement(
            """
            insert into memory_facts(
                id, owner_id, scope_type, scope_id, kind, title, body, slot_key, canonical_key, status,
                retention, confidence, importance, pinned, created_by, content_hash, created_at, updated_at,
                supersedes_fact_id
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, factId)
            statement.setString(2, ownerId.value)
            statement.setString(3, scope.type)
            statement.setString(4, scope.id)
            statement.setString(5, candidate.kind.name)
            statement.setString(6, candidate.title)
            statement.setString(7, candidate.body)
            statement.setString(8, candidate.canonicalKey)
            statement.setString(9, candidate.canonicalKey)
            statement.setString(10, MemoryFactStatus.ACTIVE.name)
            statement.setString(11, retentionForScope(scope).name)
            statement.setFloat(12, candidate.confidence)
            statement.setFloat(13, candidate.importance)
            statement.setInt(14, 0)
            statement.setString(15, "dreamer")
            statement.setString(16, contentHash)
            statement.setString(17, now)
            statement.setString(18, now)
            statement.setString(19, supersedesFactId)
            statement.executeUpdate()
        }
        insertEvidence(factId, candidate.evidenceSourceEventIds.distinct(), evidenceTextBySourceEventId)
        return factId to contentHash
    }

    private fun Connection.insertSupersedes(
        replacementFactId: String,
        supersededFactIds: List<String>,
        ownerId: MemoryOwnerId,
        now: String,
    ) {
        if (supersededFactIds.isEmpty()) return
        prepareStatement(
            """
            insert or ignore into memory_fact_supersedes(
                replacement_fact_id, superseded_fact_id, owner_id, reason, created_at
            ) values (?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            supersededFactIds.distinct().forEach { supersededFactId ->
                statement.setString(1, replacementFactId)
                statement.setString(2, supersededFactId)
                statement.setString(3, ownerId.value)
                statement.setString(4, MEMORY_MAINTENANCE_REASON_DREAMER_REGION_REWRITE)
                statement.setString(5, now)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun Connection.enqueueEmbeddingJob(
        factId: String,
        ownerId: MemoryOwnerId,
        contentHash: String,
        now: String,
    ) {
        val model = embeddingModel ?: return
        prepareStatement(
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
            statement.setString(1, factId)
            statement.setString(2, ownerId.value)
            statement.setString(3, model)
            statement.setString(4, contentHash)
            statement.setString(5, now)
            statement.setString(6, now)
            statement.setString(7, now)
            statement.executeUpdate()
        }
    }

    private fun Connection.insertEvidence(
        factId: String,
        sourceEventIds: List<String>,
        evidenceTextBySourceEventId: Map<String, String>,
    ) {
        if (sourceEventIds.isEmpty()) return
        prepareStatement(
            """
            insert or ignore into memory_fact_evidence(fact_id, source_event_id, evidence_text)
            values (?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            sourceEventIds.forEach { sourceEventId ->
                statement.setString(1, factId)
                statement.setString(2, sourceEventId)
                statement.setString(3, evidenceTextBySourceEventId.getValue(sourceEventId))
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun Connection.completeJob(
        job: MaintenanceJob,
        now: String,
    ): Int {
        return prepareStatement(
            """
            update memory_maintenance_jobs
            set status = 'DONE',
                attempt_count = attempt_count + 1,
                updated_at = ?
            where cluster_key = ? and status = 'PENDING' and latest_dirty_at = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, now)
            statement.setString(2, job.clusterKey)
            statement.setString(3, job.latestDirtyAt)
            statement.executeUpdate()
        }
    }

    private fun Connection.deferJob(job: MaintenanceJob, now: Instant): Int {
        val nextAttemptAt = now.plus(job.retryDelayMinutes(), ChronoUnit.MINUTES).toString()
        return prepareStatement(
            """
            update memory_maintenance_jobs
            set attempt_count = attempt_count + 1,
                next_attempt_at = ?,
                updated_at = ?
            where cluster_key = ? and status = 'PENDING' and latest_dirty_at = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, nextAttemptAt)
            statement.setString(2, now.toString())
            statement.setString(3, job.clusterKey)
            statement.setString(4, job.latestDirtyAt)
            statement.executeUpdate()
        }
    }

    private fun Connection.processLegacyChatMigration(
        job: MaintenanceJob,
        now: String,
    ): Int {
        val scopeId = job.legacyChatScopeId() ?: return blockUnsupportedJob(job, now)
        val jobUpdated = prepareStatement(
            """
            update memory_maintenance_jobs
            set status = 'DONE',
                attempt_count = attempt_count + 1,
                updated_at = ?
            where cluster_key = ? and status = 'PENDING' and latest_dirty_at = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, now)
            statement.setString(2, job.clusterKey)
            statement.setString(3, job.latestDirtyAt)
            statement.executeUpdate()
        }
        if (jobUpdated == 0) return 0

        val factIds = prepareStatement(
            """
            select id from memory_facts
            where owner_id = ?
              and scope_type in ('chat', 'thread')
              and scope_id = ?
              and status = ?
              and updated_at <= ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, job.ownerId)
            statement.setString(2, scopeId)
            statement.setString(3, MemoryFactStatus.ACTIVE.name)
            statement.setString(4, job.latestDirtyAt)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(rs.getString("id"))
                }
            }
        }

        prepareStatement(
            """
            update memory_facts
            set status = ?,
                updated_at = ?
            where id = ? and status = ?
            """.trimIndent()
        ).use { statement ->
            factIds.forEach { factId ->
                statement.setString(1, MemoryFactStatus.RETIRED.name)
                statement.setString(2, now)
                statement.setString(3, factId)
                statement.setString(4, MemoryFactStatus.ACTIVE.name)
                statement.addBatch()
            }
            statement.executeBatch()
        }

        return 1
    }

    private fun Connection.blockUnsupportedJob(
        job: MaintenanceJob,
        now: String,
    ): Int {
        val blockReason = "no_deterministic_action"
        val updated = prepareStatement(
            """
            update memory_maintenance_jobs
            set status = 'BLOCKED',
                attempt_count = attempt_count + 1,
                reasons = case
                    when instr(reasons, ?) > 0 then reasons
                    when trim(reasons) = '' then ?
                    else reasons || ',' || ?
                end,
                updated_at = ?
            where cluster_key = ? and status = 'PENDING' and latest_dirty_at = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, blockReason)
            statement.setString(2, blockReason)
            statement.setString(3, blockReason)
            statement.setString(4, now)
            statement.setString(5, job.clusterKey)
            statement.setString(6, job.latestDirtyAt)
            statement.executeUpdate()
        }
        return updated
    }

    private fun MaintenanceJob.isLegacyChatMigration(): Boolean =
        reasonsList().contains(MEMORY_MAINTENANCE_REASON_LEGACY_CHAT_MIGRATION) &&
            legacyChatScopeId() != null

    private fun MaintenanceJob.isDreamerRegionRewrite(): Boolean =
        reasonsList().contains(MEMORY_MAINTENANCE_REASON_DREAMER_REGION_REWRITE) &&
            parseDreamerRegionMaintenanceClusterKey(clusterKey) != null

    private fun MaintenanceJob.reasonsList(): List<String> =
        reasons.split(',').map(String::trim)

    private fun MaintenanceJob.legacyChatScopeId(): String? {
        val raw = clusterKey.removePrefix("legacy-chat:").takeIf { it != clusterKey } ?: return null
        return raw.substringAfter(':', missingDelimiterValue = "").takeIf(String::isNotBlank)
    }

    private fun MaintenanceJob.retryDelayMinutes(): Long = when (attemptCount) {
        0 -> 5
        1 -> 30
        else -> 120
    }

    private inline fun <T> Connection.inTransaction(block: Connection.() -> T): T {
        autoCommit = false
        return try {
            block().also { commit() }
        } catch (error: Exception) {
            rollback()
            throw error
        }
    }

    private companion object {
        const val DREAMER_REGION_FACT_LIMIT = 24
        const val NEIGHBORHOOD_FACT_LIMIT = 8
        const val MAX_CLUSTERS_PER_RUN = 10
        val DREAMER_DURABLE_SCOPE_TYPES = setOf("global", "project")
    }
}
