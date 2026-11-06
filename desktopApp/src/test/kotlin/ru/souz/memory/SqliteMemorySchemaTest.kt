package ru.souz.memory

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SqliteMemorySchemaTest {
    @TempDir
    lateinit var tempDir: Path

    private val target = MemoryOwnerId("desktop-owner")
    private val tables = listOf(
        "memory_source_events", "memory_facts", "memory_fact_supersedes",
        "memory_index_jobs", "memory_tombstones", "memory_maintenance_jobs",
    )

    @ParameterizedTest
    @ValueSource(ints = [1, 24])
    fun `legacy migration preserves other owners and uses bounded catalogue queries`(rows: Int) = database { connection ->
        repeat(rows) { connection.seed("legacy-$it", LEGACY_OWNER_ID) }
        connection.seed("foreign", "other-owner")
        connection.seed("target", target.value)

        val queries = connection.migrate(MemoryOwnerId("  ${target.value}  "))

        assertEquals(2, queries.count { "sqlite_master" in it })
        assertEquals(tables, queries.ownerUpdates())
        tables.forEach { table ->
            assertEquals(rows + 1, connection.count(table, target.value))
            assertEquals(1, connection.count(table, "other-owner"))
            assertEquals(0, connection.count(table, LEGACY_OWNER_ID))
        }
        val repeated = connection.migrate(target)
        assertEquals(1, repeated.count { "sqlite_master" in it })
        assertTrue(repeated.ownerUpdates().isEmpty())
        assertEquals(1, connection.count("schema_migrations", "3", "version"))
        println("Legacy migration ($rows rows per owner table): catalogue queries=${queries.count { "sqlite_master" in it }}, owner updates=${queries.ownerUpdates().size}; reopen catalogue queries=${repeated.count { "sqlite_master" in it }}")
    }

    @Test
    fun `migration discovers tables before and after supersession table creation`() = database { connection ->
        connection.seed("legacy", LEGACY_OWNER_ID)
        connection.execute("drop table memory_fact_supersedes")
        connection.execute("delete from schema_migrations where version = 6")

        val queries = connection.migrate(target)

        assertEquals(tables - "memory_fact_supersedes", queries.ownerUpdates())
        assertEquals(0, connection.count("memory_fact_supersedes", target.value))
        assertEquals(1, connection.count("memory_facts", target.value))
        assertEquals(6, connection.query("pragma user_version").single().toInt())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `discovery ignores missing tables views and unrelated owner tables`(view: Boolean) = database { connection ->
        connection.seed("legacy", LEGACY_OWNER_ID)
        connection.execute("drop table memory_index_jobs")
        connection.execute("create table unrelated(owner_id text)")
        connection.execute("insert into unrelated values ('$LEGACY_OWNER_ID')")
        if (view) connection.execute("create view memory_index_jobs as select owner_id from unrelated")

        val queries = connection.migrate(target)

        assertEquals(tables - "memory_index_jobs", queries.ownerUpdates())
        assertEquals(1, connection.count("unrelated", LEGACY_OWNER_ID))
        assertEquals(1, connection.count("memory_facts", target.value))
    }

    @Test
    fun `single existing nonlegacy owner is adopted but multiple owners are retained`() = database { connection ->
        connection.seed("previous", "previous-owner")

        val adopted = connection.migrate(target)

        assertEquals(tables + tables, adopted.ownerUpdates())
        tables.forEach { assertEquals(1, connection.count(it, target.value)) }
        connection.seed("foreign", "other-owner")
        val multiple = connection.migrate(MemoryOwnerId("new-owner"))
        assertTrue(multiple.ownerUpdates().isEmpty())
        tables.forEach { assertEquals(1, connection.count(it, target.value)) }
    }

    @Test
    fun `absent blank and legacy targets retain owner data`() = database { connection ->
        connection.seed("legacy", LEGACY_OWNER_ID)
        listOf(null, MemoryOwnerId("  "), MemoryOwnerId(LEGACY_OWNER_ID)).forEach { owner ->
            val queries = connection.migrate(owner)
            assertTrue(queries.ownerUpdates().isEmpty())
            tables.forEach { assertEquals(1, connection.count(it, LEGACY_OWNER_ID)) }
        }
    }

    @Test
    fun `repository rolls back all owner updates and migration marker after a late failure`() = runTest {
        database { connection ->
            connection.seed("legacy", LEGACY_OWNER_ID)
            connection.execute("""
                create trigger reject_owner before update of owner_id on memory_maintenance_jobs
                begin select raise(abort, 'synthetic migration failure'); end
            """.trimIndent())
        }
        val repository = SqliteMemoryRepository(tempDir.resolve("memory.db"), target)

        val failure = assertFailsWith<java.sql.SQLException> { repository.initialize() }
        assertTrue(failure.message.orEmpty().contains("synthetic migration failure"))

        DriverManager.getConnection(url()).use { connection ->
            tables.forEach { assertEquals(1, connection.count(it, LEGACY_OWNER_ID)) }
            assertEquals(0, connection.count("schema_migrations", "3", "version"))
            connection.execute("drop trigger reject_owner")
        }
        repository.initialize()
        DriverManager.getConnection(url()).use { connection ->
            tables.forEach { assertEquals(1, connection.count(it, target.value)) }
            assertEquals(listOf("ok"), connection.query("pragma integrity_check"))
        }
    }

    private fun url() = "jdbc:sqlite:${tempDir.resolve("memory.db")}"

    private fun database(block: (Connection) -> Unit) = DriverManager.getConnection(url()).use { connection ->
        SqliteMemorySchema(null).ensureCurrentSchema(connection)
        block(connection)
    }

    // Execute real SQLite statements; exclude fixture setup and count queries as well as writes.
    private fun Connection.migrate(owner: MemoryOwnerId?): List<String> {
        val executions = mutableListOf<String>()
        val connection = this
        val recording = object : Connection by connection {
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
        SqliteMemorySchema(owner).ensureCurrentSchema(recording)
        return executions
    }

    private fun List<String>.ownerUpdates() = filter { it.startsWith("update ") && "set owner_id" in it }
        .map { it.substringAfter("update ").substringBefore(" set") }

    private fun Connection.count(table: String, value: String, column: String = "owner_id"): Int =
        prepareStatement("select count(*) from $table where $column = ?").use { statement ->
            statement.setString(1, value)
            statement.executeQuery().use { it.next(); it.getInt(1) }
        }

    private fun Connection.query(sql: String): List<String> = createStatement().use { statement ->
        statement.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
    }

    private fun Connection.execute(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.seed(id: String, owner: String) {
        val statements = listOf(
            """
            insert into memory_source_events(id, owner_id, scope_type, scope_id, source_type, text, created_at)
            values (?, ?, 'global', 'global', 'manual', 'synthetic evidence', '2026-08-01T00:00:00Z')
            """,
            """
            insert into memory_facts(id, owner_id, scope_type, scope_id, kind, title, body, status,
                confidence, pinned, created_by, created_at, updated_at)
            values (?, ?, 'global', 'global', 'SEMANTIC', 'synthetic', 'synthetic body', 'ACTIVE',
                1, 0, 'manual', '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z')
            """,
            """
            insert into memory_fact_supersedes(replacement_fact_id, owner_id, superseded_fact_id, reason, created_at)
            values (?, ?, 'previous', 'synthetic', '2026-08-01T00:00:00Z')
            """,
            """
            insert into memory_index_jobs(fact_id, owner_id, embedding_model, content_hash, status,
                next_attempt_at, created_at, updated_at)
            values (?, ?, 'fixture', 'hash', 'PENDING', 't', 't', 't')
            """,
            """
            insert into memory_tombstones(id, owner_id, scope_type, scope_id, reason, created_at)
            values (?, ?, 'global', 'global', 'synthetic', '2026-08-01T00:00:00Z')
            """,
            """
            insert into memory_maintenance_jobs(cluster_key, owner_id, status, latest_dirty_at,
                next_attempt_at, created_at, updated_at)
            values (?, ?, 'PENDING', 't', 't', 't', 't')
            """,
        )
        statements.forEach { sql ->
            prepareStatement(sql.trimIndent()).use { statement ->
                statement.setString(1, id)
                statement.setString(2, owner)
                statement.executeUpdate()
            }
        }
    }
}
