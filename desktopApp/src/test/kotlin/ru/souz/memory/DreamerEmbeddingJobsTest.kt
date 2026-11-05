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

    @ParameterizedTest
    @ValueSource(ints = [1, 12])
    fun `replacement enqueues all embeddings with one prepared batch`(replacements: Int) = runTest {
        val repository = seed(replacements)
        val writes = recordEnqueues {
            assertEquals(1, worker(model).runOnce(preferences))
        }

        assertReplacement(repository, replacements, expectEmbeddings = true)
        assertEquals(1, writes.prepares)
        assertEquals(0, writes.updates)
        assertEquals(replacements, writes.batchEntries)
        assertEquals(1, writes.batches)
    }

    @Test
    fun `replacement without an embedding model does no enqueue work`() = runTest {
        val repository = seed(3)
        val writes = recordEnqueues { assertEquals(1, worker(null).runOnce(preferences)) }

        assertReplacement(repository, 3, expectEmbeddings = false)
        assertEquals(0, writes.prepares)
        assertEquals(0, writes.updates)
        assertEquals(0, writes.batches)
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
        val writes = recordEnqueues { assertEquals(0, worker.runOnce(preferences)) }

        assertEquals(0, writes.prepares)
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
                        canonicalKey = null,
                        confidence = 0.9f,
                        sourceFactIds = sources.map { it.fact.id },
                        evidenceSourceEventIds = sources.flatMap { it.evidence.map { it.sourceEvent.id } },
                    )
                }
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
            assertEquals("dreamer", fact.createdBy)
            assertEquals(2, repository.getFactDetails(fact.id)?.evidence?.size)
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

    private class Enqueues {
        var prepares = 0
        var updates = 0
        var batchEntries = 0
        var batches = 0
    }

    // Delegate to real SQLite so these counts cover the worker's actual transaction and SQL.
    private suspend fun recordEnqueues(block: suspend () -> Unit): Enqueues {
        val writes = Enqueues()
        mockkStatic(DriverManager::class)
        try {
            every { DriverManager.getConnection(jdbcUrl) } answers {
                val connection = JDBC().connect(jdbcUrl, Properties())
                object : Connection by connection {
                    override fun prepareStatement(sql: String): PreparedStatement {
                        val statement = connection.prepareStatement(sql)
                        if (!sql.startsWith("insert into memory_index_jobs(")) return statement
                        writes.prepares++
                        return object : PreparedStatement by statement {
                            override fun executeUpdate(): Int {
                                writes.updates++
                                return statement.executeUpdate()
                            }

                            override fun addBatch() {
                                writes.batchEntries++
                                statement.addBatch()
                            }

                            override fun executeBatch(): IntArray {
                                writes.batches++
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
