package ru.souz.memory

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.Properties
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.sqlite.JDBC
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqliteMemoryDeletionTest {
    @TempDir
    lateinit var tempDir: Path

    private val owner = MemoryOwnerId("deletion-owner")
    private val scope = MemoryScope("chat", "chat:target")
    private val tables = listOf(
        "memory_facts", "memory_fact_embeddings", "memory_fact_stats", "memory_index_jobs",
        "memory_fact_evidence", "memory_fact_supersedes", "memory_source_events",
        "memory_tombstones", "memory_maintenance_jobs",
    )

    @Test
    fun `scoped deletion matches individual deletes and preserves unrelated data`() = runTest {
        val scalar = fixture("scalar", 24)
        val batch = fixture("batch", 24)

        (0 until 24).forEach { scalar.repository.deleteFact("delete-$it") }
        batch.repository.deleteFactsByScope(owner, scope)

        assertEquals(scalar.snapshot(), batch.snapshot())
        assertEquals(listOf("foreign", "keep", "other"), batch.ids("memory_facts", "id"))
        listOf("memory_fact_embeddings", "memory_fact_stats", "memory_index_jobs", "memory_fact_evidence").forEach {
            assertEquals(listOf("foreign", "keep", "other"), batch.ids(it, "fact_id"))
        }
        assertEquals(listOf("keep"), batch.ids("memory_fact_supersedes", "replacement_fact_id"))
        assertEquals(listOf("orphan", "shared"), batch.ids("memory_source_events", "id"))
        assertEquals("delete-0", batch.repository.getFact("keep")?.supersedesFactId)
        assertEquals("shared", batch.repository.getFactDetails("keep")?.evidence?.single()?.sourceEvent?.id)
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 24, 1001])
    fun `scoped deletion uses bounded statements across fact and source chunks`(count: Int) = runTest {
        val fixture = fixture("count-$count", count)
        val executions = fixture.recordExecutions { fixture.repository.deleteFactsByScope(owner, scope) }
        println("Scoped deletion of $count facts: ${executions.size} prepared SQL executions")

        // At most nine statements per 400 facts, plus the scope selection. Shared evidence
        // makes a full chunk contain 401 sources, exercising the source parameter limit too.
        assertTrue(executions.size <= 1 + 9 * ((count + 399) / 400), "Executed ${executions.size} statements for $count facts")
        assertTrue(executions.all { sql -> sql.count { it == '?' } <= 999 })
        assertEquals(listOf("foreign", "keep", "other"), fixture.ids("memory_facts", "id"))
        assertEquals(listOf("orphan", "shared"), fixture.ids("memory_source_events", "id"))
        assertEquals(listOf("ok"), fixture.query("pragma integrity_check").flatten())
    }

    @Test
    fun `single fact with many sources deletes only its unused evidence`() = runTest {
        val fixture = fixture("many-sources", 1)
        fixture.connection { connection ->
            repeat(1001) { index -> connection.source("extra-$index", "delete-0") }
        }

        val executions = fixture.recordExecutions { fixture.repository.deleteFact("delete-0") }

        assertTrue(executions.size <= 10)
        assertTrue(executions.all { sql -> sql.count { it == '?' } <= 999 })
        assertNull(fixture.repository.getFact("delete-0"))
        assertNotNull(fixture.repository.getFact("keep"))
        assertEquals(listOf("orphan", "shared"), fixture.ids("memory_source_events", "id"))
    }

    @Test
    fun `source shared only by deleted facts is removed across chunk boundaries`() = runTest {
        val fixture = fixture("shared-across-chunks", 401)
        fixture.connection { connection ->
            connection.source("deleted-shared")
            repeat(401) { connection.evidence("delete-$it", "deleted-shared") }
        }

        fixture.repository.deleteFactsByScope(owner, scope)

        assertEquals(listOf("orphan", "shared"), fixture.ids("memory_source_events", "id"))
        assertEquals(listOf("foreign", "keep", "other"), fixture.ids("memory_fact_evidence", "fact_id"))
    }

    @Test
    fun `failure in a later chunk rolls back all dependent and source deletions`() = runTest {
        val fixture = fixture("rollback", 1001)
        fixture.connection { connection ->
            connection.createStatement().use {
                it.execute(
                    """
                    create trigger fail_late_delete before delete on memory_facts
                    when (select count(*) from memory_facts where id like 'delete-%') < 500
                    begin select raise(abort, 'synthetic deletion failure'); end
                    """.trimIndent()
                )
            }
        }
        val before = fixture.snapshot()

        assertFailsWith<SQLException> { fixture.repository.deleteFactsByScope(owner, scope) }

        assertEquals(before, fixture.snapshot())
    }

    @Test
    fun `empty scope and repeated or missing fact deletion leave unrelated rows untouched`() = runTest {
        val fixture = fixture("empty", 1)
        val before = fixture.snapshot()

        val executions = fixture.recordExecutions {
            fixture.repository.deleteFactsByScope(owner, MemoryScope("session", "missing"))
        }
        assertEquals(1, executions.size)
        fixture.repository.deleteFact("unknown")
        assertEquals(before, fixture.snapshot())
        fixture.repository.deleteFact("delete-0")
        val after = fixture.snapshot()
        fixture.repository.deleteFact("delete-0")
        assertEquals(after, fixture.snapshot())
    }

    private suspend fun fixture(name: String, count: Int): Fixture {
        val path = tempDir.resolve("$name.db")
        val fixture = Fixture(SqliteMemoryRepository(path), "jdbc:sqlite:${path.toAbsolutePath()}")
        fixture.repository.initialize()
        fixture.connection { connection ->
            connection.autoCommit = false
            (0 until count).forEach { index ->
                val type = if (index % 2 == 0) "chat" else "thread"
                val id = if (index % 4 < 2) "target" else "$type:target"
                connection.fact("delete-$index", owner.value, type, id, index % 2 == 0)
                connection.source("exclusive-$index", "delete-$index")
                connection.evidence("delete-$index", "shared")
            }
            connection.fact("keep", owner.value, "global", "default", true)
            connection.fact("foreign", "other-owner", "chat", "target", true)
            connection.fact("other", owner.value, "chat", "other-chat", true)
            connection.source("shared", "keep")
            connection.evidence("foreign", "shared")
            connection.evidence("other", "shared")
            connection.source("orphan")
            connection.update("update memory_facts set supersedes_fact_id = 'delete-0' where id = 'keep'")
            listOf("keep" to "delete-0", "delete-0" to "keep", "keep" to "other").forEach { (from, to) ->
                connection.update(
                    "insert into memory_fact_supersedes values (?, ?, ?, 'test', '2026-08-01T00:00:00Z')",
                    from, to, owner.value,
                )
            }
            connection.update(
                "insert into memory_tombstones(id, owner_id, scope_type, scope_id, reason, created_at) " +
                    "values ('tombstone', ?, 'chat', 'target', 'test', '2026-08-01T00:00:00Z')", owner.value,
            )
            connection.update(
                "insert into memory_maintenance_jobs(cluster_key, owner_id, status, latest_dirty_at, " +
                    "next_attempt_at, created_at, updated_at) values ('test', ?, 'PENDING', 't', 't', 't', 't')", owner.value,
            )
            connection.commit()
        }
        return fixture
    }

    private fun Connection.fact(id: String, ownerId: String, type: String, scopeId: String, active: Boolean) {
        update(
            """
            insert into memory_facts(id, owner_id, scope_type, scope_id, kind, title, body, status,
                confidence, pinned, created_by, created_at, updated_at)
            values (?, ?, ?, ?, 'SEMANTIC', ?, 'synthetic body', ?, 1, 0, 'manual',
                '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z')
            """.trimIndent(), id, ownerId, type, scopeId, id, if (active) "ACTIVE" else "RETIRED",
        )
        update("insert into memory_fact_stats values (?, '2026-08-01T00:00:00Z', 2)", id)
        update(
            "insert into memory_fact_embeddings(fact_id, embedding_model, embedding_blob, dimension, updated_at, " +
                "content_hash) values (?, 'fixture', X'0000803F', 1, 't', 'hash')", id,
        )
        update(
            """
            insert into memory_index_jobs(fact_id, owner_id, embedding_model, content_hash, status,
                next_attempt_at, created_at, updated_at)
            values (?, ?, 'fixture', 'hash', 'PENDING', 't', 't', 't')
            """.trimIndent(), id, ownerId,
        )
    }

    private fun Connection.source(id: String, factId: String? = null) {
        update(
            """
            insert into memory_source_events(id, owner_id, scope_type, scope_id, source_type, text, created_at)
            values (?, ?, 'chat', 'target', 'manual', ?, '2026-08-01T00:00:00Z')
            """.trimIndent(), id, owner.value, id,
        )
        if (factId != null) evidence(factId, id)
    }

    private fun Connection.evidence(factId: String, sourceId: String) {
        update("insert into memory_fact_evidence values (?, ?, 'synthetic evidence')", factId, sourceId)
    }

    private fun Connection.update(sql: String, vararg values: String) {
        prepareStatement(sql).use { statement ->
            values.forEachIndexed { index, value -> statement.setString(index + 1, value) }
            statement.executeUpdate()
        }
    }

    private inner class Fixture(val repository: SqliteMemoryRepository, val url: String) {
        fun <T> connection(block: (Connection) -> T): T = DriverManager.getConnection(url).use(block)

        fun query(sql: String): List<List<String?>> = connection { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rs ->
                    buildList {
                        while (rs.next()) add((1..rs.metaData.columnCount).map { column -> rs.getString(column) })
                    }
                }
            }
        }

        fun ids(table: String, column: String) = query("select $column from $table order by $column").flatten()
        fun snapshot() = tables.associateWith { query("select * from $it order by 1, 2") }

        // Keep real SQLite execution and results; count only prepared query/update executions,
        // excluding connection PRAGMAs, initialization, and fixture setup.
        suspend fun recordExecutions(block: suspend () -> Unit): List<String> {
            val executions = mutableListOf<String>()
            mockkStatic(DriverManager::class)
            try {
                every { DriverManager.getConnection(url) } answers {
                    val connection = JDBC().connect(url, Properties())
                    object : Connection by connection {
                        override fun prepareStatement(sql: String): PreparedStatement {
                            val statement = connection.prepareStatement(sql)
                            return object : PreparedStatement by statement {
                                override fun executeQuery(): ResultSet {
                                    executions += sql
                                    return statement.executeQuery()
                                }

                                override fun executeUpdate(): Int {
                                    executions += sql
                                    return statement.executeUpdate()
                                }
                            }
                        }
                    }
                }
                block()
            } finally {
                unmockkStatic(DriverManager::class)
            }
            return executions
        }
    }
}
