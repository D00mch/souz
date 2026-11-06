package ru.souz.memory

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.Properties
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.sqlite.JDBC
import org.sqlite.SQLiteException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DreamerCanonicalConflictTest {
    @TempDir
    lateinit var tempDir: Path

    private val dbPath get() = tempDir.resolve("memory.db")
    private val jdbcUrl get() = "jdbc:sqlite:${dbPath.toAbsolutePath()}"
    private val repository get() = SqliteMemoryRepository(dbPath)
    private val owner = MemoryOwnerId("canonical-owner")
    private val scope = MemoryScope.project(ProjectId("canonical-project"))
    private val time = Instant.parse("2026-08-01T10:00:00Z")
    private val key = "project.rule.canonical"
    private val preferences = MemoryMaintenancePreferences(mode = MemoryMaintenanceMode.LOCAL_ONLY)

    @ParameterizedTest
    @ValueSource(ints = [1, 10])
    fun `successful rewrites avoid conflict reads and their connections`(jobs: Int) = runTest {
        repeat(jobs) { index ->
            val target = MemoryScope.project(ProjectId("project-$index"))
            seed(2, target)
            enqueue(target)
        }
        val reads = recordReads { assertEquals(jobs, worker().runOnce(preferences)) }

        println("$jobs successful canonical rewrites: connections=${reads.connections}, conflict queries=${reads.conflicts}")
        assertEquals(0, reads.conflicts)
        // Initialization + pending jobs; each scope loads IDs, initializes/reads details and commits.
        assertEquals(2 + jobs * 4, reads.connections)
        assertEquals(jobs, count("memory_facts", "created_by = 'dreamer' and status = 'ACTIVE'"))
        assertEquals(jobs * 2, count("memory_facts", "status = 'RETIRED'"))
        assertEquals(jobs * 2, count("memory_fact_supersedes"))
        assertEquals(jobs, count("memory_index_jobs", "status = 'PENDING'"))
        assertEquals(jobs, count("memory_maintenance_jobs", "status = 'DONE' and attempt_count = 1"))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `external conflict completes job without any partial replacement`(arrivesDuringConsolidation: Boolean) = runTest {
        seed(4)
        enqueue()
        suspend fun addConflict() {
            insert("external", canonical = key, updatedAt = time.plusSeconds(100))
        }
        if (!arrivesDuringConsolidation) addConflict()
        val reads = recordReads {
            assertEquals(0, worker { input ->
                if (arrivesDuringConsolidation) addConflict()
                // A successful earlier batch entry must also roll back on the later conflict.
                input.facts.chunked(2).mapIndexed { index, facts ->
                    candidate(facts, if (index == 0) "project.rule.free" else key)
                }
            }.runOnce(preferences))
        }

        assertEquals(1, reads.conflicts)
        assertEquals(5, count("memory_facts", "status = 'ACTIVE'"))
        assertEquals(0, count("memory_facts", "created_by = 'dreamer'"))
        assertEquals(4, count("memory_fact_evidence"))
        assertEquals(0, count("memory_fact_supersedes"))
        assertEquals(0, count("memory_index_jobs"))
        assertEquals(1, count("memory_maintenance_jobs", "status = 'DONE' and attempt_count = 1"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["source", "null", "retired", "other-owner", "other-scope"])
    fun `canonical reuse null keys and unrelated facts still permit replacement`(case: String) = runTest {
        seed(2, canonical = if (case == "source") key else null)
        if (case != "source" && case != "null") {
            insert(
                "unrelated", canonical = key, updatedAt = time.plusSeconds(100),
                factOwner = if (case == "other-owner") MemoryOwnerId("other") else owner,
                target = if (case == "other-scope") MemoryScope.project(ProjectId("other")) else scope,
                status = if (case == "retired") MemoryFactStatus.RETIRED else MemoryFactStatus.ACTIVE,
            )
        }
        enqueue()
        val reads = recordReads {
            assertEquals(1, worker { listOf(candidate(it.facts, if (case == "null") null else key)) }.runOnce(preferences))
        }

        assertEquals(0, reads.conflicts)
        val replacement = repository.listFacts(MemoryFactFilter(ownerId = owner, scope = scope))
            .single { it.createdBy == "dreamer" }
        assertEquals(if (case == "null") null else key, replacement.canonicalKey)
        assertEquals(2, repository.getFactDetails(replacement.id)!!.evidence.size)
        assertEquals(2, count("memory_fact_supersedes"))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `in-region and duplicate candidate conflicts remain errors and roll back`(duplicateCandidates: Boolean) = runTest {
        seed(if (duplicateCandidates) 4 else 2)
        if (!duplicateCandidates) insert("omitted", canonical = key)
        enqueue()
        val error = assertFailsWith<SQLiteException> {
            worker { input ->
                val sources = input.facts.filterNot { it.fact.title == "omitted" }
                sources.chunked(2).map { candidate(it) }
            }.runOnce(preferences)
        }

        assertTrue(error.message.orEmpty().contains("UNIQUE constraint failed"))
        assertEquals(0, count("memory_facts", "created_by = 'dreamer'"))
        assertEquals(0, count("memory_facts", "status = 'RETIRED'"))
        assertEquals(0, count("memory_fact_supersedes"))
        assertEquals(0, count("memory_index_jobs"))
        assertEquals(1, count("memory_maintenance_jobs", "status = 'PENDING' and attempt_count = 0"))
    }

    @Test
    fun `conflict with a redirtied job does not consume the new work`() = runTest {
        seed(2)
        insert("external", canonical = key, updatedAt = time.plusSeconds(100))
        enqueue()
        assertEquals(0, worker { input ->
            connection { it.createStatement().use { s -> s.executeUpdate("update memory_maintenance_jobs set latest_dirty_at = '2099-01-01T00:00:00Z'") } }
            listOf(candidate(input.facts))
        }.runOnce(preferences))
        assertEquals(1, count("memory_maintenance_jobs", "status = 'PENDING' and attempt_count = 0"))
        assertEquals(3, count("memory_facts", "status = 'ACTIVE'"))
    }

    @Test
    fun `external conflict does not stop another scope job`() = runTest {
        seed(2)
        insert("external", canonical = key, updatedAt = time.plusSeconds(100))
        enqueue()
        val other = MemoryScope.project(ProjectId("other"))
        seed(2, other)
        enqueue(other)

        assertEquals(1, worker().runOnce(preferences))
        assertEquals(2, count("memory_maintenance_jobs", "status = 'DONE' and attempt_count = 1"))
        assertEquals(1, count("memory_facts", "created_by = 'dreamer'"))
        assertEquals(3, repository.listFacts(MemoryFactFilter(ownerId = owner, scope = scope)).size)
    }

    @Test
    fun `unrelated unique errors propagate without a conflict lookup`() = runTest {
        seed(4)
        enqueue()
        connection { connection ->
            connection.createStatement().use {
                it.executeUpdate("create unique index fixture_titles on memory_facts(title) where created_by = 'dreamer'")
            }
        }
        val reads = recordReads {
            val error = assertFailsWith<SQLiteException> {
                worker { input -> input.facts.chunked(2).mapIndexed { index, facts ->
                    candidate(facts, "project.rule.key$index")
                } }.runOnce(preferences)
            }
            assertTrue(error.message.orEmpty().contains("memory_facts.title"))
        }
        assertEquals(0, reads.conflicts)
        assertEquals(4, count("memory_facts", "status = 'ACTIVE'"))
        assertEquals(0, count("memory_facts", "created_by = 'dreamer'"))
        assertEquals(1, count("memory_maintenance_jobs", "status = 'PENDING' and attempt_count = 0"))
    }

    private suspend fun seed(size: Int, target: MemoryScope = scope, canonical: String? = null) {
        repeat(size) { index -> insert("source-$index", target, canonical = if (index == 0) canonical else null, evidence = true) }
    }

    private suspend fun insert(
        title: String,
        target: MemoryScope = scope,
        canonical: String? = null,
        updatedAt: Instant = time,
        factOwner: MemoryOwnerId = owner,
        status: MemoryFactStatus = MemoryFactStatus.ACTIVE,
        evidence: Boolean = false,
    ) {
        val body = "Synthetic durable $title has enough supporting detail for a compact replacement."
        val refs = if (evidence) {
            val source = repository.insertSourceEvent(NewMemorySourceEvent(factOwner, target, "manual", null, body))
            listOf(MemoryEvidenceRef(source, body))
        } else emptyList()
        val factId = repository.insertFact(
            NewMemoryFact(
                ownerId = factOwner, scope = target, kind = MemoryFactKind.SEMANTIC, title = title, body = body,
                canonicalKey = canonical, status = status, confidence = 0.9f, pinned = false,
                createdBy = "fixture", createdAt = time, updatedAt = updatedAt, supersedesFactId = null,
            ), refs,
        )
        connection { connection ->
            connection.prepareStatement("delete from memory_maintenance_jobs where cluster_key = ?").use {
                it.setString(1, dreamerRegionMaintenanceClusterKey(factOwner, target, factId))
                it.executeUpdate()
            }
        }
    }

    private fun candidate(facts: List<MemoryFactDetails>, canonical: String? = key) = MemoryConsolidationCandidate(
        kind = MemoryFactKind.SEMANTIC, title = "Combined", body = "Compact fact.", canonicalKey = canonical,
        confidence = 0.9f, sourceFactIds = facts.map { it.fact.id },
        evidenceSourceEventIds = facts.flatMap { it.evidence.map { detail -> detail.sourceEvent.id } },
    )

    private fun worker(consolidate: suspend (MemoryConsolidationInput) -> List<MemoryConsolidationCandidate> = { listOf(candidate(it.facts)) }) =
        MemoryMaintenanceWorker(dbPath, embeddingModel = "fixture-embedding", consolidator = object : MemoryConsolidator {
            override suspend fun consolidate(input: MemoryConsolidationInput) = consolidate.invoke(input)
        })

    private fun enqueue(target: MemoryScope = scope) = connection { connection ->
        // Explicit scope jobs exclude facts newer than the input cutoff.
        connection.prepareStatement(
            """
            insert into memory_maintenance_jobs(cluster_key, owner_id, status, priority, latest_dirty_at, reasons,
                next_attempt_at, created_at, updated_at)
            values (?, ?, 'PENDING', 100, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, dreamerRegionMaintenanceClusterKey(owner, target))
            statement.setString(2, owner.value)
            statement.setString(3, time.plusSeconds(1).toString())
            statement.setString(4, MEMORY_MAINTENANCE_REASON_DREAMER_REGION_REWRITE)
            (5..7).forEach { statement.setString(it, time.toString()) }
            statement.executeUpdate()
        }
    }

    private fun <T> connection(block: (Connection) -> T) = DriverManager.getConnection(jdbcUrl).use(block)

    private fun count(table: String, where: String = "1 = 1") = connection { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("select count(*) from $table where $where").use { it.next(); it.getInt(1) }
        }
    }

    private class Reads {
        var connections = 0
        var conflicts = 0
    }

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
                                if ("canonical_key in (" in sql) reads.conflicts++
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
