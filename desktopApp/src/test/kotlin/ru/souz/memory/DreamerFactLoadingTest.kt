package ru.souz.memory

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.util.Properties
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import org.sqlite.JDBC
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DreamerFactLoadingTest {
    @TempDir
    lateinit var tempDir: Path

    private val owner = MemoryOwnerId("dreamer-owner")
    private val scope = MemoryScope.project(ProjectId("dreamer-project"))
    private val baseTime = Instant.parse("2026-08-01T10:00:00Z")
    private val dbPath get() = tempDir.resolve("memory.db")
    private val jdbcUrl get() = "jdbc:sqlite:${dbPath.toAbsolutePath()}"

    @Test
    fun `batch matches scalar details with two queries and one connection for 24 facts`() = runTest {
        val repository = SqliteMemoryRepository(dbPath)
        val olderSource = repository.insertSourceEvent(source("older", baseTime))
        val newerSource = repository.insertSourceEvent(source("newer", baseTime.plusSeconds(1)))
        val evidence = listOf(
            MemoryEvidenceRef(olderSource, null),
            MemoryEvidenceRef(newerSource, "newer excerpt"),
            MemoryEvidenceRef("missing-source", "orphaned evidence"),
        )
        val ids = (1..24).map { index ->
            repository.insertFact(fact(index), if (index % 2 == 0) evidence else emptyList())
        }.reversed()
        var expected = emptyList<MemoryFactDetails>()
        val scalarReads = recordReads {
            expected = ids.mapNotNull { repository.getFactDetails(it) }
        }
        var actual = emptyList<MemoryFactDetails>()
        val batchReads = recordReads { actual = repository.getFactDetails(ids) }

        assertEquals(expected, actual)
        assertEquals(ids, actual.map { it.fact.id })
        assertEquals(listOf(newerSource, olderSource), actual.first().evidence.map { it.sourceEvent.id })
        assertEquals(listOf("newer excerpt", null), actual.first().evidence.map { it.evidence.evidenceText })
        assertEquals(source("newer", baseTime.plusSeconds(1)).text, actual.first().evidence.first().sourceEvent.text)
        assertTrue(actual[1].evidence.isEmpty())
        assertEquals(24, scalarReads.connections)
        assertEquals(48, scalarReads.detailQueries)
        assertEquals(1, batchReads.connections)
        assertEquals(2, batchReads.detailQueries)
    }

    @Test
    fun `batch preserves duplicate IDs and skips missing facts without reading empty evidence sets`() = runTest {
        val repository = SqliteMemoryRepository(dbPath)
        val first = repository.insertFact(fact(1), emptyList())
        val second = repository.insertFact(fact(2).copy(status = MemoryFactStatus.RETIRED), emptyList())
        val deleted = repository.insertFact(fact(3), emptyList())
        repository.deleteFact(deleted)

        val emptyReads = recordReads { assertEquals(emptyList(), repository.getFactDetails(emptyList())) }
        assertEquals(0, emptyReads.connections)
        val missingReads = recordReads {
            assertEquals(emptyList(), repository.getFactDetails(listOf(deleted, "unknown")))
        }
        assertEquals(1, missingReads.detailQueries)
        val ids = listOf(second, deleted, first, second, "unknown")
        val expected = ids.mapNotNull { repository.getFactDetails(it) }
        val reads = recordReads { assertEquals(expected, repository.getFactDetails(ids)) }
        assertEquals(2, reads.detailQueries)
        assertEquals(listOf(second, first, second), expected.map { it.fact.id })
    }

    @Test
    fun `region retains ordering filters and 24 fact limit with one pair of detail queries`() = runTest {
        val repository = SqliteMemoryRepository(dbPath)
        val sourceId = repository.insertSourceEvent(source("region", baseTime))
        val ids = (1..26).map { index ->
            repository.insertFact(fact(index), listOf(MemoryEvidenceRef(sourceId, "fact $index")))
        }
        listOf(
            fact(101), // Updated after the job's dirty cutoff.
            fact(90).copy(ownerId = MemoryOwnerId("other-owner")),
            fact(91).copy(scope = MemoryScope.project(ProjectId("other-project"))),
            fact(92).copy(status = MemoryFactStatus.RETIRED),
            fact(93).copy(retention = MemoryRetention.SESSION_LIFETIME),
        ).forEach { repository.insertFact(it, emptyList()) }
        enqueueJob()
        var input: MemoryConsolidationInput? = null
        val worker = MemoryMaintenanceWorker(dbPath, consolidator = recordingConsolidator { input = it })

        val reads = recordReads {
            assertEquals(0, worker.runOnce(MemoryMaintenancePreferences(mode = MemoryMaintenanceMode.LOCAL_ONLY)))
        }

        val selected = requireNotNull(input).facts
        assertEquals(ids.reversed().take(24), selected.map { it.fact.id })
        assertEquals((26 downTo 3).map { "fact $it" }, selected.map { it.evidence.single().evidence.evidenceText })
        assertEquals(2, reads.detailQueries)
    }

    @Test
    fun `neighborhood keeps anchor first and recent fallback order with two pairs of detail queries`() = runTest {
        val repository = SqliteMemoryRepository(dbPath)
        val ids = (1..12).map { repository.insertFact(fact(it), emptyList()) }
        val anchor = ids.first()
        enqueueJob(anchor)
        var input: MemoryConsolidationInput? = null
        val worker = MemoryMaintenanceWorker(dbPath, consolidator = recordingConsolidator { input = it })

        val reads = recordReads {
            worker.runOnce(MemoryMaintenancePreferences(mode = MemoryMaintenanceMode.LOCAL_ONLY))
        }

        assertEquals(listOf(anchor) + ids.reversed().take(7), requireNotNull(input).facts.map { it.fact.id })
        assertEquals(4, reads.detailQueries) // Anchor validation, then all seven neighbors.
    }

    @Test
    fun `foreign anchor is rejected before loading neighbors`() = runTest {
        val repository = SqliteMemoryRepository(dbPath)
        val foreignAnchor = repository.insertFact(fact(1).copy(ownerId = MemoryOwnerId("other-owner")), emptyList())
        (2..4).forEach { repository.insertFact(fact(it), emptyList()) }
        enqueueJob(foreignAnchor)
        var calls = 0
        val worker = MemoryMaintenanceWorker(dbPath, consolidator = recordingConsolidator { calls++ })

        val reads = recordReads {
            worker.runOnce(MemoryMaintenancePreferences(mode = MemoryMaintenanceMode.LOCAL_ONLY))
        }

        assertEquals(0, calls)
        assertEquals(2, reads.detailQueries)
    }

    private fun source(text: String, createdAt: Instant) = NewMemorySourceEvent(
        ownerId = owner,
        scope = scope,
        sourceType = "manual",
        sourceRef = "source-$text",
        text = text,
        metadataJson = "{\"source\":\"$text\"}",
        createdAt = createdAt,
    )

    private fun fact(index: Int) = NewMemoryFact(
        ownerId = owner,
        scope = scope,
        kind = MemoryFactKind.SEMANTIC,
        title = "Fact $index",
        body = "Durable project detail $index",
        status = MemoryFactStatus.ACTIVE,
        confidence = 0.9f,
        pinned = false,
        createdBy = "dreamer", // Queue only the job under test.
        createdAt = baseTime,
        updatedAt = baseTime.plusSeconds(index.toLong()),
        supersedesFactId = null,
    )

    private fun recordingConsolidator(record: (MemoryConsolidationInput) -> Unit) = object : MemoryConsolidator {
        override suspend fun consolidate(input: MemoryConsolidationInput): List<MemoryConsolidationCandidate> {
            record(input)
            return emptyList()
        }
    }

    private fun enqueueJob(anchor: String? = null) {
        DriverManager.getConnection(jdbcUrl).use { connection ->
            connection.prepareStatement(
                """
                insert into memory_maintenance_jobs(
                    cluster_key, owner_id, status, priority, latest_dirty_at, reasons,
                    next_attempt_at, created_at, updated_at
                ) values (?, ?, 'PENDING', 8, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, dreamerRegionMaintenanceClusterKey(owner, scope, anchor))
                statement.setString(2, owner.value)
                statement.setString(3, baseTime.plusSeconds(100).toString())
                statement.setString(4, MEMORY_MAINTENANCE_REASON_DREAMER_REGION_REWRITE)
                (5..7).forEach { statement.setString(it, baseTime.toString()) }
                statement.executeUpdate()
            }
        }
    }

    private class Reads {
        var connections = 0
        val queries = mutableListOf<String>()
        val detailQueries get() = queries.count {
            it.contains("select * from memory_facts") || it.contains("from memory_fact_evidence e")
        }
    }

    // Wrap real SQLite connections, counting actual executions rather than mocking query results.
    private suspend fun recordReads(block: suspend () -> Unit): Reads {
        val reads = Reads()
        mockkStatic(DriverManager::class)
        try {
            every { DriverManager.getConnection(jdbcUrl) } answers {
                reads.connections++
                val connection = JDBC().connect(jdbcUrl, Properties())
                object : Connection by connection {
                    override fun prepareStatement(sql: String): PreparedStatement {
                        val statement = connection.prepareStatement(sql)
                        return object : PreparedStatement by statement {
                            override fun executeQuery(): ResultSet {
                                reads.queries += sql
                                return statement.executeQuery()
                            }
                        }
                    }
                }
            }
            block()
        } finally {
            unmockkStatic(DriverManager::class)
        }
        return reads
    }
}
