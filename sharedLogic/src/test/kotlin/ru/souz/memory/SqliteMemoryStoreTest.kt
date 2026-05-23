package ru.souz.memory

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import kotlinx.coroutines.test.runTest
import ru.souz.agent.memory.MemoryEntityRecord
import ru.souz.agent.memory.MemoryEvidenceRecord
import ru.souz.agent.memory.MemoryEvidenceType
import ru.souz.agent.memory.MemoryFactRecord
import ru.souz.agent.memory.MemoryFactStatus
import ru.souz.agent.memory.MemoryObjectKind
import ru.souz.agent.memory.MemoryScope
import ru.souz.agent.memory.MemoryScopeType
import ru.souz.agent.memory.MemoryTriggerType
import ru.souz.paths.DefaultSouzPaths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SqliteMemoryStoreTest {
    @Test
    fun `schema migration creates all tables and indexes`() = runTest {
        val stateRoot = Files.createTempDirectory("sqlite-memory-schema")
        SqliteMemoryStore(paths = DefaultSouzPaths(stateRoot = stateRoot))

        val tables = sqliteNames(stateRoot.resolve("memory.db"), "table")
        val indexes = sqliteNames(stateRoot.resolve("memory.db"), "index")

        assertTrue(
            setOf(
                "memory_entity",
                "memory_entity_alias",
                "memory_fact",
                "memory_fact_evidence",
                "memory_evidence",
                "memory_episode",
                "memory_embedding_doc",
                "memory_write_attempt",
                "memory_injection_log",
            ).all(tables::contains)
        )
        assertTrue(
            setOf(
                "memory_fact_scope_status_idx",
                "memory_fact_slot_status_idx",
                "memory_fact_active_slot_unique_idx",
                "memory_fact_subject_predicate_status_idx",
                "memory_entity_normalized_key_idx",
                "memory_entity_alias_normalized_alias_idx",
                "memory_episode_scope_last_touched_idx",
                "memory_embedding_doc_status_model_idx",
            ).all(indexes::contains)
        )
    }

    @Test
    fun `accepted fact persists with evidence`() = runTest {
        val store = createStore("sqlite-memory-fact-evidence")
        val scope = userScope()
        val subject = store.resolveOrUpsertEntity(
            entity(
                scope = scope,
                entityType = "USER",
                canonicalName = "current_user",
                normalizedKey = "user/current_user",
            )
        )
        val evidence = store.insertEvidence(
            evidence(
                scope = scope,
                evidenceType = MemoryEvidenceType.USER_MESSAGE,
                sourceRef = "turn:1:user",
                contentExcerpt = "Please write tests first.",
            )
        )

        val fact = store.insertFact(
            fact(
                scope = scope,
                subjectEntityId = subject.id!!,
                predicate = "requires",
                objectKind = MemoryObjectKind.TEXT,
                objectValueText = "write tests first",
                slotKey = "user.constraints.tests_first",
            ),
            evidenceIds = listOf(evidence.id!!),
        )

        val activeFacts = store.listActiveFacts(scope)

        assertEquals(listOf(fact), activeFacts)
        assertEquals(1L, sqliteCount(store.databasePath, "memory_evidence"))
        assertEquals(1L, sqliteCount(store.databasePath, "memory_fact_evidence"))
    }

    @Test
    fun `same slot key supersedes previous active fact`() = runTest {
        val store = createStore("sqlite-memory-supersede")
        val scope = userScope()
        val subject = store.resolveOrUpsertEntity(
            entity(
                scope = scope,
                entityType = "USER",
                canonicalName = "current_user",
                normalizedKey = "user/current_user",
            )
        )
        val firstEvidence = store.insertEvidence(
            evidence(
                scope = scope,
                evidenceType = MemoryEvidenceType.USER_MESSAGE,
                sourceRef = "turn:1:user",
                contentExcerpt = "Write tests first.",
            )
        )
        val secondEvidence = store.insertEvidence(
            evidence(
                scope = scope,
                evidenceType = MemoryEvidenceType.USER_MESSAGE,
                sourceRef = "turn:2:user",
                contentExcerpt = "Actually, write tests before implementation.",
            )
        )
        val first = store.insertFact(
            fact(
                scope = scope,
                subjectEntityId = subject.id!!,
                predicate = "requires",
                objectKind = MemoryObjectKind.TEXT,
                objectValueText = "write tests first",
                slotKey = "user.constraints.tests_first",
            ),
            evidenceIds = listOf(firstEvidence.id!!),
        )

        val second = store.insertFact(
            fact(
                scope = scope,
                subjectEntityId = subject.id!!,
                predicate = "requires",
                objectKind = MemoryObjectKind.TEXT,
                objectValueText = "write tests before implementation",
                slotKey = "user.constraints.tests_first",
            ),
            evidenceIds = listOf(secondEvidence.id!!),
        )

        assertEquals(listOf(second), store.listActiveFacts(scope))
        assertEquals(
            listOf(MemoryFactStatus.SUPERSEDED.name, second.id),
            sqliteRow(
                store.databasePath,
                """
                select status, invalidated_by_fact_id
                from memory_fact
                where id = ?
                """.trimIndent(),
                first.id!!,
            )
        )
    }

    @Test
    fun `only one active fact per slot key`() = runTest {
        val store = createStore("sqlite-memory-active-slot")
        val scope = userScope()
        val subject = store.resolveOrUpsertEntity(
            entity(
                scope = scope,
                entityType = "USER",
                canonicalName = "current_user",
                normalizedKey = "user/current_user",
            )
        )
        val firstEvidence = store.insertEvidence(evidence(scope = scope, sourceRef = "turn:1:user"))
        val secondEvidence = store.insertEvidence(evidence(scope = scope, sourceRef = "turn:2:user"))

        store.insertFact(
            fact(
                scope = scope,
                subjectEntityId = subject.id!!,
                predicate = "prefers_language",
                objectKind = MemoryObjectKind.TEXT,
                objectValueText = "ru",
                slotKey = "user.profile.language",
            ),
            evidenceIds = listOf(firstEvidence.id!!),
        )
        store.insertFact(
            fact(
                scope = scope,
                subjectEntityId = subject.id!!,
                predicate = "prefers_language",
                objectKind = MemoryObjectKind.TEXT,
                objectValueText = "en",
                slotKey = "user.profile.language",
            ),
            evidenceIds = listOf(secondEvidence.id!!),
        )

        assertEquals(
            1L,
            sqliteCount(
                store.databasePath,
                "memory_fact where slot_key = 'user.profile.language' and status = 'ACTIVE'",
            )
        )
    }

    @Test
    fun `write attempt diagnostics persist`() = runTest {
        val store = createStore("sqlite-memory-write-attempt")
        val scope = userScope()

        val id = store.logWriteAttempt(
            scope = scope,
            turnRef = "turn-1",
            triggerType = MemoryTriggerType.USER_PROFILE_SIGNAL,
            inputExcerpt = "Please write tests first.",
            candidatesJson = """[{"predicate":"requires"}]""",
            acceptedCount = 1,
            rejectedCount = 0,
            rejectionReasonsJson = null,
        )

        assertEquals(
            listOf("turn-1", "USER_PROFILE_SIGNAL", "1", "0"),
            sqliteRow(
                store.databasePath,
                """
                select turn_ref, trigger_type, accepted_count, rejected_count
                from memory_write_attempt
                where id = ?
                """.trimIndent(),
                id,
            )
        )
    }

    @Test
    fun `injection diagnostics persist`() = runTest {
        val store = createStore("sqlite-memory-injection-log")
        val scope = userScope()

        val id = store.logInjection(
            scope = scope,
            turnRef = "turn-2",
            queryExcerpt = "How should I implement this?",
            selectedRecordIds = listOf("fact-1", "episode-2"),
            renderedPacket = "<memory>- Important constraint: write tests first</memory>",
            estimatedTokens = 42,
        )

        assertEquals(
            listOf("turn-2", "[\"fact-1\",\"episode-2\"]", "42"),
            sqliteRow(
                store.databasePath,
                """
                select turn_ref, selected_record_ids_json, estimated_tokens
                from memory_injection_log
                where id = ?
                """.trimIndent(),
                id,
            )
        )
    }

    @Test
    fun `latest activity scope follows most recent diagnostic row`() = runTest {
        val store = createStore("sqlite-memory-latest-scope")
        val userScope = userScope()
        val threadScope = MemoryScope(MemoryScopeType.THREAD, "thread-42")
        store.logWriteAttempt(
            scope = userScope,
            turnRef = "turn-1",
            triggerType = MemoryTriggerType.TASK_STATE_CHANGE,
            inputExcerpt = "Older activity",
            candidatesJson = "[]",
            acceptedCount = 0,
            rejectedCount = 1,
            rejectionReasonsJson = """["LLM_OUTPUT_INVALID"]""",
            createdAt = Instant.parse("2026-05-21T10:00:00Z"),
        )
        store.logInjection(
            scope = threadScope,
            turnRef = "turn-2",
            queryExcerpt = "Newer activity",
            selectedRecordIds = emptyList(),
            renderedPacket = "",
            estimatedTokens = 0,
            createdAt = Instant.parse("2026-05-21T11:00:00Z"),
        )

        assertEquals(threadScope, store.latestActivityScope())
    }

    @Test
    fun `graph snapshot returns entity edges`() = runTest {
        val store = createStore("sqlite-memory-graph-edges")
        val scope = MemoryScope(MemoryScopeType.PROJECT, "souz")
        val project = store.resolveOrUpsertEntity(
            entity(
                scope = scope,
                entityType = "PROJECT",
                canonicalName = "Souz",
                normalizedKey = "project/souz",
            )
        )
        val module = store.resolveOrUpsertEntity(
            entity(
                scope = scope,
                entityType = "MODULE",
                canonicalName = "agent",
                normalizedKey = "module/agent",
            )
        )
        val evidence = store.insertEvidence(evidence(scope = scope, sourceRef = "file:AGENTS.md"))
        store.insertFact(
            fact(
                scope = scope,
                subjectEntityId = project.id!!,
                predicate = "uses_module",
                objectKind = MemoryObjectKind.ENTITY,
                objectEntityId = module.id!!,
                slotKey = "project.souz.uses_module.agent",
            ),
            evidenceIds = listOf(evidence.id!!),
        )

        val snapshot = store.graphSnapshot(scope)

        assertTrue(
            snapshot.edges.any { edge ->
                edge.subjectEntityId == project.id &&
                    edge.objectEntityId == module.id &&
                    edge.predicate == "uses_module"
            }
        )
    }

    @Test
    fun `literal facts are attributes not noisy graph nodes`() = runTest {
        val store = createStore("sqlite-memory-graph-attributes")
        val scope = userScope()
        val subject = store.resolveOrUpsertEntity(
            entity(
                scope = scope,
                entityType = "USER",
                canonicalName = "current_user",
                normalizedKey = "user/current_user",
            )
        )
        val evidence = store.insertEvidence(evidence(scope = scope, sourceRef = "turn:1:user"))
        store.insertFact(
            fact(
                scope = scope,
                subjectEntityId = subject.id!!,
                predicate = "prefers_language",
                objectKind = MemoryObjectKind.TEXT,
                objectValueText = "ru",
                slotKey = "user.profile.language",
            ),
            evidenceIds = listOf(evidence.id!!),
        )

        val snapshot = store.graphSnapshot(scope)

        assertTrue(
            snapshot.attributes.any { attribute ->
                attribute.subjectEntityId == subject.id &&
                    attribute.predicate == "prefers_language" &&
                    attribute.value == "ru"
            }
        )
        assertFalse(snapshot.edges.any { it.predicate == "prefers_language" })
        assertEquals(1, snapshot.entities.size)
        assertNotNull(snapshot.evidenceIndex.values.flatten().singleOrNull())
    }

    @Test
    fun `timeline contains supersede transitions`() = runTest {
        val store = createStore("sqlite-memory-graph-timeline")
        val scope = userScope()
        val subject = store.resolveOrUpsertEntity(
            entity(
                scope = scope,
                entityType = "USER",
                canonicalName = "current_user",
                normalizedKey = "user/current_user",
            )
        )
        val firstEvidence = store.insertEvidence(evidence(scope = scope, sourceRef = "turn:1:user"))
        val secondEvidence = store.insertEvidence(evidence(scope = scope, sourceRef = "turn:2:user"))
        val first = store.insertFact(
            fact(
                scope = scope,
                subjectEntityId = subject.id!!,
                predicate = "prefers_language",
                objectKind = MemoryObjectKind.TEXT,
                objectValueText = "ru",
                slotKey = "user.profile.language",
            ),
            evidenceIds = listOf(firstEvidence.id!!),
        )
        val second = store.insertFact(
            fact(
                scope = scope,
                subjectEntityId = subject.id!!,
                predicate = "prefers_language",
                objectKind = MemoryObjectKind.TEXT,
                objectValueText = "en",
                slotKey = "user.profile.language",
            ),
            evidenceIds = listOf(secondEvidence.id!!),
        )

        val snapshot = store.graphSnapshot(scope)

        assertTrue(
            snapshot.timelineEvents.any { event ->
                event.type == "SUPERSEDED" &&
                    event.factId == first.id &&
                    event.relatedFactId == second.id
            }
        )
    }

    @Test
    fun `legacy memory evidence schema is migrated before new writes`() = runTest {
        val stateRoot = Files.createTempDirectory("sqlite-memory-legacy-evidence")
        val databasePath = stateRoot.resolve("memory.db")
        val legacyInstant = Instant.parse("2026-05-21T10:00:00Z")
        createLegacyEvidenceDatabase(databasePath, legacyInstant)

        val store = SqliteMemoryStore(paths = DefaultSouzPaths(stateRoot = stateRoot))
        val stored = store.insertEvidence(
            evidence(
                scope = userScope(),
                sourceRef = "turn:2:user",
                contentExcerpt = "new evidence",
            )
        )

        assertEquals(
            listOf("scope_type", "scope_id", "evidence_type", "content_excerpt", "content_json"),
            sqliteColumns(databasePath, "memory_evidence").filter {
                it in setOf("scope_type", "scope_id", "evidence_type", "content_excerpt", "content_json")
            }
        )
        assertEquals(2L, sqliteCount(databasePath, "memory_evidence"))
        assertEquals(
            listOf("GLOBAL", "legacy-memory-evidence", "USER_MESSAGE", "legacy excerpt"),
            sqliteFirstRow(
                databasePath,
                """
                select scope_type, scope_id, evidence_type, content_excerpt
                from memory_evidence
                where id = '1'
                """.trimIndent(),
            )
        )
        assertEquals(
            listOf(stored.id!!, "USER", "local-user"),
            sqliteFirstRow(
                databasePath,
                """
                select id, scope_type, scope_id
                from memory_evidence
                where source_ref = 'turn:2:user'
                """.trimIndent(),
            )
        )
        assertEquals(
            listOf("1", "2"),
            sqliteSingleColumn(
                databasePath,
                """
                select cast(version as text)
                from memory_schema_migration
                order by version
                """.trimIndent(),
            )
        )
    }

    private fun createStore(prefix: String): SqliteMemoryStore {
        val stateRoot = Files.createTempDirectory(prefix)
        return SqliteMemoryStore(paths = DefaultSouzPaths(stateRoot = stateRoot))
    }

    private fun createLegacyEvidenceDatabase(
        databasePath: Path,
        createdAt: Instant,
    ) {
        DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    create table memory_schema_migration(
                        version integer primary key,
                        name text not null,
                        applied_at text not null
                    )
                    """.trimIndent()
                )
                statement.execute(
                    """
                    insert into memory_schema_migration(version, name, applied_at)
                    values (1, 'db/memory/sqlite/V1__memory_core.sql', '${createdAt}')
                    """.trimIndent()
                )
                statement.execute(
                    """
                    create table memory_evidence (
                        id integer primary key autoincrement,
                        fact_id text not null,
                        source_type text not null,
                        source_ref text,
                        excerpt text not null,
                        confidence real not null,
                        created_at integer not null,
                        source_pipeline text,
                        source_task text,
                        source_node_set text,
                        source_agent_id text
                    )
                    """.trimIndent()
                )
                statement.execute(
                    """
                    insert into memory_evidence(
                        fact_id,
                        source_type,
                        source_ref,
                        excerpt,
                        confidence,
                        created_at,
                        source_pipeline
                    )
                    values (
                        'legacy-fact',
                        'USER_MESSAGE',
                        'turn:1:user',
                        'legacy excerpt',
                        0.9,
                        ${createdAt.toEpochMilli()},
                        'legacy'
                    )
                    """.trimIndent()
                )
            }
        }
    }

    private fun userScope(): MemoryScope =
        MemoryScope(MemoryScopeType.USER, "local-user")

    private fun entity(
        scope: MemoryScope,
        entityType: String,
        canonicalName: String,
        normalizedKey: String,
    ): MemoryEntityRecord =
        MemoryEntityRecord(
            scope = scope,
            entityType = entityType,
            canonicalName = canonicalName,
            displayName = canonicalName,
            normalizedKey = normalizedKey,
        )

    private fun evidence(
        scope: MemoryScope,
        evidenceType: MemoryEvidenceType = MemoryEvidenceType.USER_MESSAGE,
        sourceRef: String,
        contentExcerpt: String? = null,
    ): MemoryEvidenceRecord =
        MemoryEvidenceRecord(
            scope = scope,
            evidenceType = evidenceType,
            sourceRef = sourceRef,
            sourceHash = null,
            contentExcerpt = contentExcerpt,
            contentJson = null,
        )

    private fun fact(
        scope: MemoryScope,
        subjectEntityId: String,
        predicate: String,
        objectKind: MemoryObjectKind,
        objectEntityId: String? = null,
        objectValueText: String? = null,
        slotKey: String? = null,
    ): MemoryFactRecord =
        MemoryFactRecord(
            scope = scope,
            subjectEntityId = subjectEntityId,
            predicate = predicate,
            objectKind = objectKind,
            objectEntityId = objectEntityId,
            objectValueText = objectValueText,
            objectValueJson = null,
            slotKey = slotKey,
            confidence = 0.95,
            status = MemoryFactStatus.ACTIVE,
            reasonToStore = "test",
            createdAt = Instant.parse("2026-05-21T10:00:00Z"),
            validFrom = Instant.parse("2026-05-21T10:00:00Z"),
        )

    private fun sqliteNames(databasePath: Path, type: String): Set<String> =
        DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
            connection.prepareStatement(
                """
                select name
                from sqlite_master
                where type = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, type)
                statement.executeQuery().use { resultSet ->
                    buildSet {
                        while (resultSet.next()) {
                            add(resultSet.getString("name"))
                        }
                    }
                }
            }
        }

    private fun sqliteCount(databasePath: Path, fromClause: String): Long =
        DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
            connection.prepareStatement("select count(*) from $fromClause").use { statement ->
                statement.executeQuery().use { resultSet ->
                    resultSet.next()
                    resultSet.getLong(1)
                }
            }
        }

    private fun sqliteColumns(databasePath: Path, tableName: String): List<String> =
        DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
            connection.prepareStatement("pragma table_info($tableName)").use { statement ->
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(resultSet.getString("name"))
                        }
                    }
                }
            }
        }

    private fun sqliteRow(
        databasePath: Path,
        sql: String,
        id: String,
    ): List<String?> =
        DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, id)
                statement.executeQuery().use { resultSet ->
                    resultSet.next()
                    List(resultSet.metaData.columnCount) { index ->
                        resultSet.getString(index + 1)
                    }
                }
            }
        }

    private fun sqliteFirstRow(
        databasePath: Path,
        sql: String,
    ): List<String?> =
        DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.executeQuery().use { resultSet ->
                    resultSet.next()
                    List(resultSet.metaData.columnCount) { index ->
                        resultSet.getString(index + 1)
                    }
                }
            }
        }

    private fun sqliteSingleColumn(
        databasePath: Path,
        sql: String,
    ): List<String?> =
        DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(resultSet.getString(1))
                        }
                    }
                }
            }
        }
}
