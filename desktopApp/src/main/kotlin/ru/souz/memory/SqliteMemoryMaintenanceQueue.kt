package ru.souz.memory

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant

internal fun Connection.enqueueDreamerRegionIfNeeded(
    factId: String,
    ownerId: MemoryOwnerId,
    scope: MemoryScope,
    status: MemoryFactStatus,
    retention: MemoryRetention,
    createdBy: String,
    dirtyAt: Instant,
) {
    if (status != MemoryFactStatus.ACTIVE || createdBy == "dreamer") return
    if (retention != MemoryRetention.DURABLE || scope.type !in DREAMER_DURABLE_SCOPE_TYPES) return
    if (countActiveDurableFacts(ownerId, scope) < 2) return
    prepareStatement(ENQUEUE_DREAMER_REGION_SQL).use { statement ->
        statement.bindDreamerRegion(DreamerMaintenanceTarget(ownerId, scope, factId), dirtyAt)
        statement.executeUpdate()
    }
}

private fun PreparedStatement.bindDreamerRegion(target: DreamerMaintenanceTarget, dirtyAt: Instant) {
    val now = Instant.now().toString()
    setString(1, dreamerRegionMaintenanceClusterKey(target.ownerId, target.scope, target.anchorFactId))
    setString(2, target.ownerId.value)
    setInt(3, dreamerPriority(target.scope))
    setString(4, dirtyAt.toString())
    setString(5, MEMORY_MAINTENANCE_REASON_DREAMER_REGION_REWRITE)
    setString(6, now)
    setString(7, now)
    setString(8, now)
}

private fun Connection.countActiveDurableFacts(ownerId: MemoryOwnerId, scope: MemoryScope): Int =
    prepareStatement(
        """
        select count(*) from memory_facts
        where owner_id = ? and scope_type = ? and scope_id = ? and status = ? and retention = ?
        """.trimIndent()
    ).use { statement ->
        statement.setString(1, ownerId.value)
        statement.setString(2, scope.type)
        statement.setString(3, scope.id)
        statement.setString(4, MemoryFactStatus.ACTIVE.name)
        statement.setString(5, MemoryRetention.DURABLE.name)
        statement.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
    }

internal fun Connection.enqueueDreamerRegionsForRetrievedFacts(
    factIds: List<String>,
    retrievedAt: Instant,
) {
    if (factIds.isEmpty()) return
    val targets = prepareStatement(
        """
        select id, owner_id, scope_type, scope_id, status, retention, created_by
        from memory_facts
        where id in (${placeholders(factIds.size)})
        """.trimIndent()
    ).use { statement ->
        factIds.forEachIndexed { index, factId -> statement.setString(index + 1, factId) }
        statement.executeQuery().use { rs ->
            buildList {
                while (rs.next()) {
                    val scope = MemoryScope(rs.getString("scope_type"), rs.getString("scope_id")).normalized()
                    val status = enumValueOrDefault(
                        rs.getStringOrDefault("status", MemoryFactStatus.ACTIVE.name), MemoryFactStatus.ACTIVE,
                    )
                    val retention = enumValueOrDefault(
                        rs.getStringOrDefault("retention", MemoryRetention.DURABLE.name), MemoryRetention.DURABLE,
                    )
                    if (status != MemoryFactStatus.ACTIVE || rs.getString("created_by") == "dreamer") continue
                    if (retention != MemoryRetention.DURABLE || scope.type !in DREAMER_DURABLE_SCOPE_TYPES) continue
                    add(DreamerMaintenanceTarget(
                        MemoryOwnerId(rs.getString("owner_id") ?: LEGACY_OWNER_ID), scope, rs.getString("id"),
                    ))
                }
            }
        }
    }
    if (targets.isEmpty()) return
    val regions = targets.map { it.copy(anchorFactId = null) }.distinct()
    // Queue writes do not change facts. Count each distinct owner/scope once, including
    // unselected and Dreamer-created facts, then retain a separate job for every anchor.
    val eligibleRegions = eligibleDreamerRegions(regions).toSet()
    val eligible = targets.filter { it.copy(anchorFactId = null) in eligibleRegions }
    if (eligible.isEmpty()) return
    prepareStatement(ENQUEUE_DREAMER_REGION_SQL).use { statement ->
        eligible.forEach { target ->
            statement.bindDreamerRegion(target, retrievedAt)
            statement.addBatch()
        }
        statement.executeBatch()
    }
}

private fun Connection.eligibleDreamerRegions(regions: List<DreamerMaintenanceTarget>): List<DreamerMaintenanceTarget> =
    regions.chunked(300).flatMap { chunk -> // Three parameters per region, below SQLite's legacy 999 limit.
        prepareStatement(
            """
            with regions(owner_id, scope_type, scope_id) as (values ${chunk.joinToString { "(?, ?, ?)" }})
            select r.owner_id, r.scope_type, r.scope_id
            from regions r join memory_facts f
              on f.owner_id = r.owner_id and f.scope_type = r.scope_type and f.scope_id = r.scope_id
            where f.status = ? and f.retention = ?
            group by r.owner_id, r.scope_type, r.scope_id
            having count(*) >= 2
            """.trimIndent()
        ).use { statement ->
            chunk.forEachIndexed { index, region ->
                statement.setString(index * 3 + 1, region.ownerId.value)
                statement.setString(index * 3 + 2, region.scope.type)
                statement.setString(index * 3 + 3, region.scope.id)
            }
            statement.setString(chunk.size * 3 + 1, MemoryFactStatus.ACTIVE.name)
            statement.setString(chunk.size * 3 + 2, MemoryRetention.DURABLE.name)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(DreamerMaintenanceTarget(
                        MemoryOwnerId(rs.getString("owner_id")),
                        MemoryScope(rs.getString("scope_type"), rs.getString("scope_id")),
                    ))
                }
            }
        }
    }

private fun dreamerPriority(scope: MemoryScope): Int = when (scope.type) {
    "project" -> 8
    "global" -> 6
    else -> 0
}

private fun placeholders(size: Int): String = List(size) { "?" }.joinToString(", ")

private fun ResultSet.getStringOrDefault(name: String, default: String): String =
    getString(name) ?: default

private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, default: T): T =
    enumValues<T>().firstOrNull { it.name == value } ?: default

private val DREAMER_DURABLE_SCOPE_TYPES = setOf("global", "project")

private val ENQUEUE_DREAMER_REGION_SQL = """
    insert into memory_maintenance_jobs(
        cluster_key,
        owner_id,
        status,
        priority,
        latest_dirty_at,
        reasons,
        next_attempt_at,
        created_at,
        updated_at
    ) values (?, ?, 'PENDING', ?, ?, ?, ?, ?, ?)
    on conflict(cluster_key) do update set
        owner_id = excluded.owner_id,
        status = 'PENDING',
        priority = max(memory_maintenance_jobs.priority, excluded.priority),
        latest_dirty_at = max(memory_maintenance_jobs.latest_dirty_at, excluded.latest_dirty_at),
        reasons = case
            when instr(memory_maintenance_jobs.reasons, excluded.reasons) > 0 then memory_maintenance_jobs.reasons
            when trim(memory_maintenance_jobs.reasons) = '' then excluded.reasons
            else memory_maintenance_jobs.reasons || ',' || excluded.reasons
        end,
        attempt_count = 0,
        next_attempt_at = excluded.next_attempt_at,
        updated_at = excluded.updated_at
    """.trimIndent()
