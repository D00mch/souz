package ru.souz.memory

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SqliteCanonicalKeyMigrationTest {
    @TempDir
    lateinit var tempDir: Path

    private val dbPath get() = tempDir.resolve("memory.db")
    private val key = "project.rule.color"

    @ParameterizedTest
    @ValueSource(ints = [1, 32, 1001])
    fun `normalization database work is bounded and migration is not repeated`(rows: Int) = database { connection ->
        repeat(rows) { connection.seed("fact-$it", "PROJECT/RULE/Color/$it") }

        val calls = connection.migrate()

        repeat(rows) { connection.assertFact("fact-$it", "$key.$it") }
        assertEquals(1, connection.query("select count(*) from schema_migrations where version = 4").single().toInt())
        println("Canonical normalization ($rows rows): $calls")
        assertEquals(Calls(reads = 2, updatePreparations = 1, batchEntries = rows, batches = 1), calls)
        val reopened = connection.migrate()
        assertEquals(Calls(), reopened)
    }

    @Test
    fun `ordered normalization preserves collisions self keys owners scopes and inactive facts`() = database { connection ->
        connection.seed("a-winner", "PROJECT/RULE/Color")
        connection.seed("b-loser", "project_rule_color")
        connection.seed("c-retired", "project:rule:color", status = "RETIRED")
        connection.seed("d-owner", "project-rule-color", owner = "another-owner")
        connection.seed("e-scope-type", "project.rule/color", scopeType = "session")
        connection.seed("f-scope-id", "project.rule:color", scopeId = "another-scope")
        connection.seed("self", "project.rule.self")
        connection.seed("invalid", "not an allowed key")
        connection.seed("long", "project.rule." + "x".repeat(100))
        connection.seed("unicode", "ＰＲＯＪＥＣＴ／ＲＵＬＥ／Ｕｎｉｃｏｄｅ")
        connection.seed("untouched", null, canonical = "project.rule.external")
        connection.seed("external-conflict", "PROJECT/RULE/External")
        connection.seed("inactive-blocker", null, canonical = "project.rule.inactive", status = "RETIRED")
        connection.seed("inactive-reuse", "PROJECT/RULE/Inactive")

        connection.migrate()

        mapOf(
            "a-winner" to key, "b-loser" to null, "c-retired" to key, "d-owner" to key,
            "e-scope-type" to key, "f-scope-id" to key, "self" to "project.rule.self",
            "invalid" to null, "long" to null, "unicode" to "project.rule.unicode",
            "external-conflict" to null, "inactive-reuse" to "project.rule.inactive",
        ).forEach { (id, expected) -> connection.assertFact(id, expected) }
        assertEquals(listOf("original"), connection.query("select content_hash from memory_facts where id = 'untouched'"))
        assertEquals(listOf("RETIRED"), connection.query("select status from memory_facts where id = 'c-retired'"))
        assertEquals(listOf("PROJECT/RULE/Color"), connection.query("select slot_key from memory_facts where id = 'a-winner'"))
        assertEquals(listOf("ok"), connection.query("pragma integrity_check"))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `conflict checks see keys released earlier but not keys released later`(releaseFirst: Boolean) = database { connection ->
        connection.seed("holder", "invalid", canonical = key, updated = if (releaseFirst) "3" else "1")
        connection.seed("claimant", "PROJECT/RULE/Color", updated = "2")

        connection.migrate()

        connection.assertFact("holder", null)
        connection.assertFact("claimant", if (releaseFirst) key else null)
    }

    @Test
    fun `newest timestamp wins before lexical id order`() = database { connection ->
        connection.seed("a-older", "PROJECT/RULE/Color", updated = "1")
        connection.seed("z-newer", "project_rule_color", updated = "2")

        connection.migrate()

        connection.assertFact("z-newer", key)
        connection.assertFact("a-older", null)
    }

    @Test
    fun `empty legacy input does not read active keys or execute an update batch`() = database { connection ->
        connection.seed("external", null, canonical = key)

        val calls = connection.migrate()

        assertEquals(Calls(reads = 1), calls)
        assertEquals(listOf("original"), connection.query("select content_hash from memory_facts"))
    }

    @Test
    fun `repository rolls back earlier updates and migration marker on a late batch failure`() = runTest {
        database { connection ->
            connection.seed("a-first", "PROJECT/RULE/First")
            connection.seed("z-fail", "PROJECT/RULE/Fail")
            connection.execute("""
                create trigger reject_normalization before update of canonical_key on memory_facts
                when old.id = 'z-fail'
                begin select raise(abort, 'synthetic normalization failure'); end
            """.trimIndent())
        }
        val repository = SqliteMemoryRepository(dbPath)

        val failure = assertFailsWith<SQLException> { repository.initialize() }
        assertTrue(failure.message.orEmpty().contains("synthetic normalization failure"))
        DriverManager.getConnection(url()).use { connection ->
            assertEquals(listOf("PROJECT/RULE/First"), connection.query("select canonical_key from memory_facts where id = 'a-first'"))
            assertEquals(listOf("original", "original"), connection.query("select content_hash from memory_facts order by id"))
            assertEquals(listOf("0"), connection.query("select count(*) from schema_migrations where version = 4"))
            connection.execute("drop trigger reject_normalization")
        }
        repository.initialize()
        DriverManager.getConnection(url()).use { connection ->
            connection.assertFact("a-first", "project.rule.first")
            connection.assertFact("z-fail", "project.rule.fail")
            assertEquals(listOf("ok"), connection.query("pragma integrity_check"))
        }
    }

    private fun url() = "jdbc:sqlite:$dbPath"

    private fun database(block: (Connection) -> Unit) = DriverManager.getConnection(url()).use { connection ->
        SqliteMemorySchema(null).ensureCurrentSchema(connection)
        connection.execute("delete from schema_migrations where version = 4")
        block(connection)
    }

    private data class Calls(
        var reads: Int = 0,
        var updatePreparations: Int = 0,
        var updates: Int = 0,
        var batchEntries: Int = 0,
        var batches: Int = 0,
    )

    // Count actual JDBC work against SQLite, excluding fixture setup and assertion queries.
    private fun Connection.migrate(): Calls {
        val calls = Calls()
        val connection = this
        val recording = object : Connection by connection {
            override fun prepareStatement(sql: String): PreparedStatement {
                val statement = connection.prepareStatement(sql)
                val normalization = "set canonical_key" in sql
                if (normalization) calls.updatePreparations++
                return object : PreparedStatement by statement {
                    override fun executeQuery(): ResultSet {
                        if ("from memory_facts" in sql) calls.reads++
                        return statement.executeQuery()
                    }

                    override fun executeUpdate(): Int {
                        if (normalization) calls.updates++
                        return statement.executeUpdate()
                    }

                    override fun addBatch() {
                        if (normalization) calls.batchEntries++
                        statement.addBatch()
                    }

                    override fun executeBatch(): IntArray {
                        if (normalization) calls.batches++
                        return statement.executeBatch()
                    }
                }
            }
        }
        SqliteMemorySchema(null).ensureCurrentSchema(recording)
        return calls
    }

    private fun Connection.seed(
        id: String,
        slot: String?,
        canonical: String? = slot,
        status: String = "ACTIVE",
        owner: String = "owner",
        scopeType: String = "project",
        scopeId: String = "project",
        updated: String = "2",
    ) {
        prepareStatement("""
            insert into memory_facts(id, owner_id, scope_type, scope_id, kind, title, body,
                slot_key, canonical_key, content_hash, status, confidence, pinned, created_by, created_at, updated_at)
            values (?, ?, ?, ?, 'SEMANTIC', ?, ?, ?, ?, 'original', ?, 1, 0, 'manual', '1', ?)
        """.trimIndent()).use { statement ->
            listOf(id, owner, scopeType, scopeId, "title $id", "body $id", slot, canonical, status, updated)
                .forEachIndexed { index, value -> statement.setString(index + 1, value) }
            statement.executeUpdate()
        }
    }

    private fun Connection.assertFact(id: String, canonical: String?) {
        prepareStatement("select canonical_key, content_hash from memory_facts where id = ?").use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { rs ->
                assertTrue(rs.next(), id)
                assertEquals(canonical, rs.getString(1), id)
                assertEquals(stableMemoryContentHash("title $id", "body $id", MemoryFactKind.SEMANTIC, canonical), rs.getString(2), id)
            }
        }
    }

    private fun Connection.query(sql: String): List<String> = createStatement().use { statement ->
        statement.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
    }

    private fun Connection.execute(sql: String) = createStatement().use { it.execute(sql) }
}
