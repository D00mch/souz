package ru.souz.memory

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.SQLException
import java.time.Instant
import java.util.Properties
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.sqlite.JDBC
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DreamerEmbeddingJobsTest {
    @TempDir
    lateinit var tempDir: Path

    private val owner = MemoryOwnerId("embedding-owner")
    private val scope = MemoryScope.project(ProjectId("embedding-project"))
    private val model = "fixture-embedding"
    private val baseTime = Instant.parse("2026-08-01T10:00:00Z")
    private val dbPath get() = tempDir.resolve("memory.db")
    private val jdbcUrl get() = "jdbc:sqlite:${dbPath.toAbsolutePath()}"
    private val preferences = MemoryMaintenancePreferences(mode = MemoryMaintenanceMode.LOCAL_ONLY)
    private var candidates = emptyList<MemoryConsolidationCandidate>()

    @ParameterizedTest
    @ValueSource(ints = [1, 12])
    fun `replacement batches facts evidence supersession and embeddings with one statement each`(replacements: Int) = runTest {
        val repository = seed(replacements)
        val writes = recordInserts {
            assertEquals(1, worker(model).runOnce(preferences))
        }

        assertReplacement(repository, replacements, expectEmbeddings = true)
        writes.forEach { (table, calls) ->
            println("$replacements replacements: $table prepares=${calls.prepares} updates=${calls.updates} entries=${calls.batchEntries} batches=${calls.batches}")
        }
        writes.forEach { (table, calls) ->
            assertEquals(1, calls.prepares, table)
            assertEquals(0, calls.updates, table)
            val entriesPerReplacement = when (table) {
                "memory_fact_evidence", "memory_fact_supersedes" -> 2
                else -> 1
            }
            assertEquals(replacements * entriesPerReplacement, calls.batchEntries, table)
            assertEquals(1, calls.batches, table)
        }
    }

    @Test
    fun `replacement without an embedding model does no enqueue work`() = runTest {
        val repository = seed(3)
        val writes = recordInserts { assertEquals(1, worker(null).runOnce(preferences)) }

        assertReplacement(repository, 3, expectEmbeddings = false)
        assertEquals(0, writes.getValue("memory_index_jobs").prepares)
    }

    @ParameterizedTest
    @ValueSource(strings = ["memory_facts", "memory_fact_evidence", "memory_fact_supersedes"])
    fun `later replacement insert failure rolls back the complete replacement and permits retry`(table: String) = runTest {
        val repository = seed(3)
        connection { connection ->
            connection.createStatement().use {
                it.executeUpdate(
                    """
                    create trigger fail_later_insert before insert on $table
                    when (select count(*) from $table) = ${if (table == "memory_fact_supersedes") 3 else 7}
                    begin select raise(abort, 'fixture insert failure'); end
                    """.trimIndent()
                )
            }
        }

        val error = assertFailsWith<SQLException> { worker(model).runOnce(preferences) }
        assertTrue(error.message.orEmpty().contains("fixture insert failure"))
        connection { connection ->
            assertEquals(6, connection.count("memory_facts", "status = 'ACTIVE'"))
            assertEquals(0, connection.count("memory_facts", "created_by = 'dreamer'"))
            assertEquals(6, connection.count("memory_fact_evidence"))
            assertEquals(0, connection.count("memory_fact_supersedes"))
            assertEquals(0, connection.count("memory_index_jobs"))
            assertEquals(1, connection.count("memory_maintenance_jobs", "status = 'PENDING' and attempt_count = 0"))
            connection.createStatement().use { it.executeUpdate("drop trigger fail_later_insert") }
        }

        assertEquals(1, worker(model).runOnce(preferences))
        assertReplacement(repository, 3, expectEmbeddings = true)
    }

    @Test
    fun `later enqueue failure rolls back replacement and earlier batch entries and permits retry`() = runTest {
        val repository = seed(3)
        connection { connection ->
            connection.createStatement().use {
                it.executeUpdate(
                    """
                    create trigger fail_second_embedding before insert on memory_index_jobs
                    when (select count(*) from memory_index_jobs) = 1
                    begin select raise(abort, 'fixture enqueue failure'); end
                    """.trimIndent()
                )
            }
        }

        val error = assertFailsWith<SQLException> { worker(model).runOnce(preferences) }
        assertTrue(error.message.orEmpty().contains("fixture enqueue failure"))
        connection { connection ->
            assertEquals(6, connection.count("memory_facts", "status = 'ACTIVE'"))
            assertEquals(0, connection.count("memory_facts", "created_by = 'dreamer'"))
            assertEquals(6, connection.count("memory_fact_evidence"))
            assertEquals(0, connection.count("memory_fact_supersedes"))
            assertEquals(0, connection.count("memory_index_jobs"))
            assertEquals(1, connection.count("memory_maintenance_jobs", "status = 'PENDING' and attempt_count = 0"))
            connection.createStatement().use { it.executeUpdate("drop trigger fail_second_embedding") }
        }

        assertEquals(1, worker(model).runOnce(preferences))
        assertReplacement(repository, 3, expectEmbeddings = true)
    }

    @Test
    fun `job dirtied during consolidation leaves sources and embedding queue untouched`() = runTest {
        seed(3)
        val worker = worker(model) {
            connection { connection ->
                connection.createStatement().use {
                    it.executeUpdate("update memory_maintenance_jobs set latest_dirty_at = '2099-01-01T00:00:00Z'")
                }
            }
        }
        val writes = recordInserts { assertEquals(0, worker.runOnce(preferences)) }

        assertTrue(writes.values.all { it.prepares == 0 })
        connection { connection ->
            assertEquals(6, connection.count("memory_facts", "status = 'ACTIVE'"))
            assertEquals(0, connection.count("memory_fact_supersedes"))
            assertEquals(0, connection.count("memory_index_jobs"))
            assertEquals(1, connection.count("memory_maintenance_jobs", "status = 'PENDING' and attempt_count = 0"))
        }
    }

    private suspend fun seed(replacements: Int): SqliteMemoryRepository {
        val repository = SqliteMemoryRepository(dbPath)
        repeat(replacements * 2) { index ->
            val body = "Synthetic durable source $index contains enough detail to be compacted into a shorter replacement."
            val source = repository.insertSourceEvent(
                NewMemorySourceEvent(ownerId = owner, scope = scope, sourceType = "manual", sourceRef = null, text = body)
            )
            repository.insertFact(
                NewMemoryFact(
                    ownerId = owner,
                    scope = scope,
                    kind = MemoryFactKind.SEMANTIC,
                    title = "Source $index",
                    body = body,
                    status = MemoryFactStatus.ACTIVE,
                    confidence = 0.9f,
                    pinned = false,
                    createdBy = "fixture",
                    createdAt = baseTime,
                    updatedAt = baseTime,
                    supersedesFactId = null,
                ),
                listOf(MemoryEvidenceRef(source, body)),
            )
        }
        connection { connection ->
            connection.createStatement().use { it.executeUpdate("delete from memory_maintenance_jobs") }
            connection.prepareStatement(
                """
                insert into memory_maintenance_jobs(
                    cluster_key, owner_id, status, priority, latest_dirty_at, reasons,
                    next_attempt_at, created_at, updated_at
                ) values (?, ?, 'PENDING', 8, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, dreamerRegionMaintenanceClusterKey(owner, scope))
                statement.setString(2, owner.value)
                statement.setString(3, baseTime.plusSeconds(1).toString())
                statement.setString(4, MEMORY_MAINTENANCE_REASON_DREAMER_REGION_REWRITE)
                (5..7).forEach { statement.setString(it, baseTime.toString()) }
                statement.executeUpdate()
            }
        }
        return repository
    }

    private fun worker(embeddingModel: String?, beforeReturn: () -> Unit = {}) = MemoryMaintenanceWorker(
        dbPath,
        embeddingModel = embeddingModel,
        consolidator = object : MemoryConsolidator {
            override suspend fun consolidate(input: MemoryConsolidationInput): List<MemoryConsolidationCandidate> {
                beforeReturn()
                return input.facts.chunked(2).mapIndexed { index, sources ->
                    MemoryConsolidationCandidate(
                        kind = MemoryFactKind.SEMANTIC,
                        title = "Replacement $index",
                        body = "Compact fact $index.",
                        canonicalKey = if (index % 2 == 0) "project.rule.replacement$index" else null,
                        confidence = 0.9f,
                        importance = 0.7f,
                        sourceFactIds = sources.map { it.fact.id }.let { it + it.first() },
                        evidenceSourceEventIds = sources.flatMap { it.evidence.map { it.sourceEvent.id } }.let { it + it.first() },
                    )
                }.also { candidates = it }
            }
        },
    )

    private suspend fun assertReplacement(
        repository: SqliteMemoryRepository,
        replacements: Int,
        expectEmbeddings: Boolean,
    ) {
        val active = repository.listFacts(MemoryFactFilter(ownerId = owner, scope = scope))
            .filter { it.status == MemoryFactStatus.ACTIVE }
        assertEquals(replacements, active.size)
        active.forEach { fact ->
            val candidate = candidates.single { it.title == fact.title }
            assertEquals("dreamer", fact.createdBy)
            assertEquals(owner, fact.ownerId)
            assertEquals(scope, fact.scope)
            assertEquals(candidate.kind, fact.kind)
            assertEquals(candidate.body, fact.body)
            assertEquals(candidate.canonicalKey, fact.canonicalKey)
            assertEquals(MemoryRetention.DURABLE, fact.retention)
            assertEquals(candidate.confidence, fact.confidence)
            assertEquals(candidate.importance, fact.importance)
            assertEquals(false, fact.pinned)
            assertEquals(candidate.sourceFactIds.first(), fact.supersedesFactId)
            assertEquals(fact.createdAt, fact.updatedAt)
            assertEquals(stableMemoryContentHash(fact.title, fact.body, fact.kind, fact.canonicalKey), fact.contentHash)
            val evidence = repository.getFactDetails(fact.id)!!.evidence
            assertEquals(candidate.evidenceSourceEventIds.toSet(), evidence.map { it.sourceEvent.id }.toSet())
            assertEquals(2, evidence.size)
            evidence.forEach { assertEquals(it.sourceEvent.text, it.evidence.evidenceText) }
            connection { connection ->
                connection.prepareStatement(
                    "select * from memory_fact_supersedes where replacement_fact_id = ?"
                ).use { statement ->
                    statement.setString(1, fact.id)
                    statement.executeQuery().use { rs ->
                        val supersededIds = mutableSetOf<String>()
                        while (rs.next()) {
                            supersededIds += rs.getString("superseded_fact_id")
                            assertEquals(owner.value, rs.getString("owner_id"))
                            assertEquals(MEMORY_MAINTENANCE_REASON_DREAMER_REGION_REWRITE, rs.getString("reason"))
                            assertEquals(fact.createdAt.toString(), rs.getString("created_at"))
                        }
                        assertEquals(candidate.sourceFactIds.toSet(), supersededIds)
                    }
                }
            }
        }
        connection { connection ->
            assertEquals(replacements * 2, connection.count("memory_facts", "status = 'RETIRED'"))
            assertEquals(replacements * 2, connection.count("memory_fact_supersedes"))
            assertEquals(1, connection.count("memory_maintenance_jobs", "status = 'DONE' and attempt_count = 1"))
            assertEquals(if (expectEmbeddings) replacements else 0, connection.count("memory_index_jobs"))
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    select j.*, f.content_hash as fact_hash, f.created_at as fact_created_at
                    from memory_index_jobs j join memory_facts f on f.id = j.fact_id
                    """.trimIndent()
                ).use { rs ->
                    val queuedIds = mutableSetOf<String>()
                    while (rs.next()) {
                        queuedIds += rs.getString("fact_id")
                        assertEquals(owner.value, rs.getString("owner_id"))
                        assertEquals(model, rs.getString("embedding_model"))
                        assertEquals(rs.getString("fact_hash"), rs.getString("content_hash"))
                        assertEquals("PENDING", rs.getString("status"))
                        assertEquals(0, rs.getInt("attempt_count"))
                        listOf("next_attempt_at", "created_at", "updated_at").forEach {
                            assertEquals(rs.getString("fact_created_at"), rs.getString(it))
                        }
                    }
                    assertEquals(if (expectEmbeddings) active.map { it.id }.toSet() else emptySet(), queuedIds)
                }
            }
        }
    }

    private fun Connection.count(table: String, where: String = "1 = 1"): Int =
        createStatement().use { statement ->
            statement.executeQuery("select count(*) from $table where $where").use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }

    private fun <T> connection(block: (Connection) -> T): T = DriverManager.getConnection(jdbcUrl).use(block)

    private class Inserts {
        var prepares = 0
        var updates = 0
        var batchEntries = 0
        var batches = 0
    }

    // Delegate to real SQLite so these counts cover the worker's actual transaction and SQL.
    private suspend fun recordInserts(block: suspend () -> Unit): Map<String, Inserts> {
        val writes = listOf("memory_facts", "memory_fact_evidence", "memory_fact_supersedes", "memory_index_jobs")
            .associateWith { Inserts() }
        mockkStatic(DriverManager::class)
        try {
            every { DriverManager.getConnection(jdbcUrl) } answers {
                val connection = JDBC().connect(jdbcUrl, Properties())
                object : Connection by connection {
                    override fun prepareStatement(sql: String): PreparedStatement {
                        val statement = connection.prepareStatement(sql)
                        val table = writes.keys.firstOrNull {
                            sql.startsWith("insert into $it(") || sql.startsWith("insert or ignore into $it(")
                        } ?: return statement
                        val calls = writes.getValue(table)
                        calls.prepares++
                        return object : PreparedStatement by statement {
                            override fun executeUpdate(): Int {
                                calls.updates++
                                return statement.executeUpdate()
                            }

                            override fun addBatch() {
                                calls.batchEntries++
                                statement.addBatch()
                            }

                            override fun executeBatch(): IntArray {
                                calls.batches++
                                return statement.executeBatch()
                            }
                        }
                    }
                }
            }
            block()
        } finally {
            unmockkStatic(DriverManager::class)
        }
        return writes
    }
}
