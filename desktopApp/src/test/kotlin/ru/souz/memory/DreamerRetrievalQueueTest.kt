package ru.souz.memory

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DreamerRetrievalQueueTest {
    @TempDir
    lateinit var tempDir: Path

    private val owner = MemoryOwnerId("retrieval-owner")
    private val scope = MemoryScope("project", "fixture")
    private val retrievedAt = Instant.parse("2026-08-01T10:00:00Z")

    @ParameterizedTest
    @ValueSource(ints = [1, 24])
    fun `retrieval matches scalar jobs with bounded reads and one prepared batch`(size: Int) = database { connection ->
        val facts = (1..size).map { Fact("fact-$it") }
        (facts + Fact("companion", createdBy = "dreamer")).forEach { connection.seed(it) }
        val scalar = Calls()
        facts.forEach { fact ->
            scalar.wrap(connection).enqueueDreamerRegionIfNeeded(
                fact.id, fact.ownerId, fact.scope, MemoryFactStatus.ACTIVE, MemoryRetention.DURABLE,
                fact.createdBy, retrievedAt,
            )
        }
        val expected = connection.jobs()
        connection.createStatement().use { it.executeUpdate("delete from memory_maintenance_jobs") }
        val batch = Calls()

        batch.wrap(connection).enqueueDreamerRegionsForRetrievedFacts(facts.map { it.id } + "fact-1" + "missing", retrievedAt)

        assertEquals(expected, connection.jobs())
        assertEquals(size, connection.jobs().size) // Every retrieved fact retains its own anchor.
        assertEquals(size, scalar.queries.size)
        assertEquals(size, scalar.prepares.size)
        assertEquals(size, scalar.updates)
        assertEquals(2, batch.queries.size)
        assertEquals(1, batch.prepares.size)
        assertEquals(0, batch.updates)
        assertEquals(size, batch.entries)
        assertEquals(1, batch.batches)
        println("Retrieval of $size facts: scalar reads=${scalar.queries.size}, upsert prepares=${scalar.prepares.size}, updates=${scalar.updates}; retrieval reads=${batch.queries.size}, upsert prepares=${batch.prepares.size}, batches=${batch.batches}, entries=${batch.entries}")
    }

    @Test
    fun `eligibility counts all exact owner scope facts and preserves legacy parsing`() = database { connection ->
        val otherOwner = MemoryOwnerId("other-owner")
        val global = MemoryScope("global", "fixture")
        val singleton = MemoryScope("project", "singleton")
        val facts = listOf(
            Fact("project"),
            Fact("dreamer", createdBy = "dreamer"), // Counts toward the threshold but cannot be an anchor.
            Fact("retired", status = "RETIRED"),
            Fact("session-retention", retention = "SESSION_LIFETIME"),
            Fact("session-scope", scope = MemoryScope("session", "fixture")),
            Fact("chat-scope", scope = MemoryScope("chat", "fixture")),
            Fact("foreign", ownerId = otherOwner), // Another owner's two facts cannot make this eligible.
            Fact("global", scope = global), // The same scope ID with a different type cannot count either.
            Fact("singleton", scope = singleton),
            Fact("retired-companion", scope = singleton, status = "RETIRED"),
            Fact("session-companion", scope = singleton, retention = "SESSION_LIFETIME"),
            Fact("fallback", status = "unknown", retention = "unknown"),
            Fact("normalized", scope = MemoryScope(" PROJECT ", "project:fixture")),
            Fact("legacy", ownerId = MemoryOwnerId(LEGACY_OWNER_ID)),
            Fact("legacy-companion", ownerId = MemoryOwnerId(LEGACY_OWNER_ID), createdBy = "dreamer"),
            Fact("eligible-global", scope = MemoryScope("global", "global")),
            Fact("global-companion", scope = MemoryScope("global", "global"), createdBy = "dreamer"),
        )
        facts.forEach { connection.seed(it) }

        connection.enqueueDreamerRegionsForRetrievedFacts(facts.map { it.id } + "deleted", retrievedAt)

        val expected = listOf("project", "fallback", "normalized", "legacy", "eligible-global").map { id ->
            val fact = facts.single { it.id == id }
            dreamerRegionMaintenanceClusterKey(fact.ownerId, fact.scope, id)
        }.toSet()
        assertEquals(expected, connection.jobs().map { it[0] }.toSet())
        assertEquals("6", connection.jobs().single { it[0] == dreamerRegionMaintenanceClusterKey(owner, MemoryScope("global", "global"), "eligible-global") }[3])
        assertEquals(facts.size, connection.rowCount("memory_facts"))
    }

    @Test
    fun `upsert preserves priority latest dirty reasons and creation while resetting pending attempts`() = database { connection ->
        val reasons = listOf("", "other", "other,$MEMORY_MAINTENANCE_REASON_DREAMER_REGION_REWRITE")
        val createdAt = retrievedAt.minusSeconds(100).toString()
        reasons.forEachIndexed { index, reason ->
            val fact = Fact("conflict-$index")
            connection.seed(fact)
            connection.prepareStatement(
                """
                insert into memory_maintenance_jobs(cluster_key, owner_id, status, priority, latest_dirty_at,
                    reasons, attempt_count, next_attempt_at, created_at, updated_at)
                values (?, 'old-owner', 'BLOCKED', ?, ?, ?, 7, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, dreamerRegionMaintenanceClusterKey(owner, scope, fact.id))
                statement.setInt(2, if (index == 0) 10 else 1)
                statement.setString(3, retrievedAt.plusSeconds(if (index == 0) 1 else -1).toString())
                statement.setString(4, reason)
                statement.setString(5, "2099-01-01T00:00:00Z")
                statement.setString(6, createdAt)
                statement.setString(7, createdAt)
                statement.executeUpdate()
            }
        }
        val before = Instant.now()

        connection.enqueueDreamerRegionsForRetrievedFacts(reasons.indices.map { "conflict-$it" }, retrievedAt)

        val after = Instant.now()
        val jobs = connection.jobs()
        assertEquals(listOf("10", "8", "8"), jobs.map { it[3] })
        assertEquals(listOf(retrievedAt.plusSeconds(1).toString(), retrievedAt.toString(), retrievedAt.toString()), jobs.map { it[4] })
        assertEquals(listOf(MEMORY_MAINTENANCE_REASON_DREAMER_REGION_REWRITE,
            "other,$MEMORY_MAINTENANCE_REASON_DREAMER_REGION_REWRITE",
            "other,$MEMORY_MAINTENANCE_REASON_DREAMER_REGION_REWRITE"), jobs.map { it[5] })
        assertTrue(jobs.all { it[1] == owner.value && it[2] == "PENDING" && it[6] == "0" })
        connection.createStatement().use { statement ->
            statement.executeQuery("select next_attempt_at, created_at, updated_at from memory_maintenance_jobs").use { rs ->
                while (rs.next()) {
                    val updated = Instant.parse(rs.getString(3))
                    assertTrue(updated >= before && updated <= after)
                    assertEquals(updated.toString(), rs.getString(1))
                    assertEquals(createdAt, rs.getString(2))
                }
            }
        }
    }

    @Test
    fun `many regions use parameter bounded grouped reads and retain caller transaction ownership`() = database { connection ->
        val facts = (1..301).map { Fact("region-$it", scope = MemoryScope("project", "scope-$it")) }
        facts.forEach { fact ->
            connection.seed(fact)
            connection.seed(fact.copy(id = "${fact.id}-companion", createdBy = "dreamer"))
        }
        val calls = Calls()
        connection.autoCommit = false

        calls.wrap(connection).enqueueDreamerRegionsForRetrievedFacts(facts.map { it.id }, retrievedAt)

        assertEquals(301, connection.rowCount("memory_maintenance_jobs"))
        assertEquals(3, calls.queries.size)
        assertTrue(calls.queries.all { sql -> sql.count { it == '?' } <= 999 })
        assertEquals(1, calls.batches)
        assertFalse(connection.autoCommit)
        connection.rollback()
        assertEquals(0, connection.rowCount("memory_maintenance_jobs"))
        assertEquals(602, connection.rowCount("memory_facts"))
    }

    @Test
    fun `empty missing and ineligible inputs do not prepare queue writes`() = database { connection ->
        val calls = Calls()
        val measured = calls.wrap(connection)
        measured.enqueueDreamerRegionsForRetrievedFacts(emptyList(), retrievedAt)
        assertTrue(calls.queries.isEmpty())
        connection.seed(Fact("dreamer", createdBy = "dreamer"))
        measured.enqueueDreamerRegionsForRetrievedFacts(listOf("missing", "dreamer"), retrievedAt)
        assertEquals(1, calls.queries.size)
        assertTrue(calls.prepares.isEmpty())
        assertEquals(0, calls.batches)
    }

    @Test
    fun `repository retrieval keeps distinct statistics and one job per eligible anchor`() = runTest {
        val dbPath = tempDir.resolve("memory.db")
        val repository = SqliteMemoryRepository(dbPath)
        repository.initialize()
        val jdbcUrl = "jdbc:sqlite:${dbPath.toAbsolutePath()}"
        DriverManager.getConnection(jdbcUrl).use { connection ->
            connection.seed(Fact("first"))
            connection.seed(Fact("second"))
            connection.seed(Fact("session", scope = MemoryScope("session", "fixture")))
        }

        repeat(2) { repository.recordRetrieval(listOf("first", "second", "first", "session")) }

        DriverManager.getConnection(jdbcUrl).use { connection ->
            assertEquals(2, connection.jobs().size)
            connection.createStatement().use { statement ->
                statement.executeQuery("select retrieval_count from memory_fact_stats").use { rs ->
                    val counts = buildList { while (rs.next()) add(rs.getInt(1)) }
                    assertEquals(listOf(2, 2, 2), counts)
                }
            }
        }
    }

    @Test
    fun `batch write failure propagates and caller can roll back all jobs`() = database { connection ->
        connection.seed(Fact("first"))
        connection.seed(Fact("second"))
        val rejectedKey = dreamerRegionMaintenanceClusterKey(owner, scope, "second")
        connection.createStatement().use { statement ->
            statement.executeUpdate("""
                create trigger reject_job before insert on memory_maintenance_jobs
                when new.cluster_key = '$rejectedKey'
                begin select raise(abort, 'synthetic queue failure'); end
            """.trimIndent())
        }
        connection.autoCommit = false

        assertFailsWith<SQLException> {
            connection.enqueueDreamerRegionsForRetrievedFacts(listOf("first", "second"), retrievedAt)
        }

        assertFalse(connection.autoCommit)
        connection.rollback()
        assertEquals(0, connection.rowCount("memory_maintenance_jobs"))
        assertEquals(2, connection.rowCount("memory_facts"))
    }

    private data class Fact(
        val id: String,
        val ownerId: MemoryOwnerId = MemoryOwnerId("retrieval-owner"),
        val scope: MemoryScope = MemoryScope("project", "fixture"),
        val status: String = "ACTIVE",
        val retention: String = "DURABLE",
        val createdBy: String = "manual",
    )

    private fun database(block: (Connection) -> Unit) {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            SqliteMemorySchema(null).ensureCurrentSchema(connection)
            block(connection)
        }
    }

    private fun Connection.seed(fact: Fact) {
        prepareStatement(
            """
            insert into memory_facts(id, owner_id, scope_type, scope_id, kind, title, body, status, retention,
                confidence, pinned, created_by, created_at, updated_at)
            values (?, ?, ?, ?, 'SEMANTIC', 'Synthetic fact', 'Synthetic body', ?, ?, 0.9, 0, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            listOf(fact.id, fact.ownerId.value, fact.scope.type, fact.scope.id, fact.status, fact.retention,
                fact.createdBy, retrievedAt.toString(), retrievedAt.toString()).forEachIndexed { index, value ->
                statement.setString(index + 1, value)
            }
            statement.executeUpdate()
        }
    }

    private fun Connection.jobs(): List<List<String>> = createStatement().use { statement ->
        statement.executeQuery(
            "select cluster_key, owner_id, status, priority, latest_dirty_at, reasons, attempt_count from memory_maintenance_jobs order by cluster_key"
        ).use { rs -> buildList { while (rs.next()) add((1..7).map { rs.getString(it) }) } }
    }

    private fun Connection.rowCount(table: String): Int = createStatement().use { statement ->
        statement.executeQuery("select count(*) from $table").use { it.next(); it.getInt(1) }
    }

    // Delegate every call to real SQLite; count JDBC executions, not predicted work or timings.
    private class Calls {
        val queries = mutableListOf<String>()
        val prepares = mutableListOf<String>()
        var updates = 0
        var entries = 0
        var batches = 0

        fun wrap(connection: Connection): Connection = object : Connection by connection {
            override fun prepareStatement(sql: String): PreparedStatement {
                val statement = connection.prepareStatement(sql)
                val queueWrite = sql.startsWith("insert into memory_maintenance_jobs(")
                if (queueWrite) prepares += sql
                return object : PreparedStatement by statement {
                    override fun executeQuery(): ResultSet {
                        queries += sql
                        return statement.executeQuery()
                    }

                    override fun executeUpdate(): Int {
                        if (queueWrite) updates++
                        return statement.executeUpdate()
                    }

                    override fun addBatch() {
                        if (queueWrite) entries++
                        statement.addBatch()
                    }

                    override fun executeBatch(): IntArray {
                        if (queueWrite) batches++
                        return statement.executeBatch()
                    }
                }
            }
        }
    }
}
