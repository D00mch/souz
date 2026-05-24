@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package ru.souz.memory

import java.nio.file.Files
import java.sql.DriverManager
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MemoryCoreTest {
    @Test
    fun `createManualFact stores source event fact evidence and embedding`() = runTest {
        val fixture = createFixture()

        val fact = fixture.memoryService.createManualFact(
            CreateMemoryFactInput(
                scope = projectScope(),
                kind = MemoryFactKind.PROJECT_RULE,
                title = "Use SQLite first",
                body = "Memory should be implemented on desktop first with SQLite.",
            )
        )

        val details = fixture.repository.getFactDetails(fact.id)

        assertNotNull(details)
        assertEquals(MemoryFactStatus.ACTIVE, details.fact.status)
        assertEquals("user", details.fact.createdBy)
        assertEquals(1, details.evidence.size)
        assertEquals("manual", details.evidence.single().sourceEvent.sourceType)
        assertTrue(details.evidence.single().sourceEvent.text.contains("Use SQLite first"))

        val hits = fixture.repository.searchFacts(
            scopes = listOf(projectScope()),
            queryEmbedding = fixture.embedder.embedQuery("sqlite desktop memory"),
            limit = 5,
        )
        assertEquals(listOf(fact.id), hits.map { it.fact.id })
    }

    @Test
    fun `getFactDetails returns fact and evidence`() = runTest {
        val fixture = createFixture()
        val fact = fixture.memoryService.createManualFact(
            CreateMemoryFactInput(
                scope = globalScope(),
                kind = MemoryFactKind.PREFERENCE,
                title = "Short answers",
                body = "User prefers short direct answers.",
            )
        )

        val details = fixture.repository.getFactDetails(fact.id)

        assertNotNull(details)
        assertEquals(fact.id, details.fact.id)
        assertEquals(1, details.evidence.size)
        assertEquals(fact.id, details.evidence.single().evidence.factId)
        assertTrue(details.evidence.single().sourceEvent.text.contains("short direct answers"))
    }

    @Test
    fun `updateFact updates text and recalculates embedding`() = runTest {
        val fixture = createFixture()
        val fact = fixture.memoryService.createManualFact(
            CreateMemoryFactInput(
                scope = projectScope(),
                kind = MemoryFactKind.PROJECT_DECISION,
                title = "Initial storage",
                body = "Use Postgres for memory storage.",
            )
        )

        val updated = fixture.memoryService.updateFact(
            factId = fact.id,
            patch = MemoryFactPatch(
                title = "Desktop storage",
                body = "Use SQLite for desktop memory storage.",
            )
        )

        assertEquals("Desktop storage", updated.title)
        assertEquals("Use SQLite for desktop memory storage.", updated.body)

        val hits = fixture.repository.searchFacts(
            scopes = listOf(projectScope()),
            queryEmbedding = fixture.embedder.embedQuery("sqlite desktop memory"),
            limit = 5,
        )
        assertEquals(fact.id, hits.first().fact.id)
        assertTrue(hits.first().score > 0.5f)
    }

    @Test
    fun `updateFact updates scope and preserves discoverability in new scope`() = runTest {
        val fixture = createFixture()
        val fact = fixture.memoryService.createManualFact(
            CreateMemoryFactInput(
                scope = projectScope(),
                kind = MemoryFactKind.PROJECT_DECISION,
                title = "Scope target",
                body = "This fact should move to chat scope.",
            )
        )

        val updated = fixture.memoryService.updateFact(
            factId = fact.id,
            patch = MemoryFactPatch(
                scope = chatScope("chat-7"),
            )
        )

        assertEquals(chatScope("chat-7"), updated.scope)
        assertTrue(
            fixture.repository.listFacts(
                MemoryFactFilter(scope = projectScope())
            ).none { it.id == fact.id }
        )
        assertEquals(
            listOf(fact.id),
            fixture.repository.listFacts(
                MemoryFactFilter(scope = chatScope("chat-7"))
            ).map { it.id }
        )

        val hits = fixture.repository.searchFacts(
            scopes = listOf(chatScope("chat-7")),
            queryEmbedding = fixture.embedder.embedQuery("scope move chat"),
            limit = 5,
        )
        assertEquals(fact.id, hits.first().fact.id)
    }

    @Test
    fun `legacy memory facts schema is migrated on first access`() = runTest {
        val dbDir = Files.createTempDirectory("souz-memory-legacy-test-")
        val dbPath = dbDir.resolve("memory.db")
        seedLegacyMemoryFactsSchema(dbPath)

        val repository = SqliteMemoryRepository(dbPath)
        val service = MemoryService(repository, FakeEmbeddingClient())

        val facts = service.listFacts(MemoryFactFilter(statuses = setOf(MemoryFactStatus.ACTIVE)))
        val migrated = facts.single()
        val details = repository.getFactDetails(migrated.id)

        assertEquals("legacy-fact-1", migrated.id)
        assertEquals(MemoryScope("global", "global"), migrated.scope)
        assertEquals(MemoryFactKind.PREFERENCE, migrated.kind)
        assertEquals("User Preference Theme", migrated.title)
        assertEquals("User prefers concise dark theme dashboards.", migrated.body)
        assertEquals("system", migrated.createdBy)
        assertNotNull(details)
        assertEquals(1, details.evidence.size)
        assertEquals("legacy_v0", details.evidence.single().sourceEvent.sourceType)
        assertEquals("user.preference.theme", details.evidence.single().sourceEvent.sourceRef)
    }

    @Test
    fun `legacy migrated facts are retrievable for prompt`() = runTest {
        val dbDir = Files.createTempDirectory("souz-memory-legacy-search-test-")
        val dbPath = dbDir.resolve("memory.db")
        seedLegacyMemoryFactsSchema(dbPath)

        val repository = SqliteMemoryRepository(dbPath)
        val memoryService = MemoryService(repository, FakeEmbeddingClient())

        val block = memoryService.retrieveForPrompt(
            scopes = listOf(globalScope()),
            query = "dark theme dashboards",
            limit = 5,
        )

        assertEquals(listOf("legacy-fact-1"), block.facts.map { it.id })
        assertTrue(block.rendered.contains("dark theme dashboards"))
    }

    @Test
    fun `retireFact marks fact as retired`() = runTest {
        val fixture = createFixture()
        val fact = fixture.memoryService.createManualFact(
            CreateMemoryFactInput(
                scope = globalScope(),
                kind = MemoryFactKind.SEMANTIC,
                title = "Desktop app",
                body = "Souz is a desktop app.",
            )
        )

        fixture.memoryService.retireFact(fact.id)

        val stored = fixture.repository.getFact(fact.id)
        assertNotNull(stored)
        assertEquals(MemoryFactStatus.RETIRED, stored.status)
    }

    @Test
    fun `deleteFact marks fact as deleted`() = runTest {
        val fixture = createFixture()
        val fact = fixture.memoryService.createManualFact(
            CreateMemoryFactInput(
                scope = globalScope(),
                kind = MemoryFactKind.SEMANTIC,
                title = "Desktop app",
                body = "Souz is a desktop app.",
            )
        )

        fixture.memoryService.deleteFact(fact.id)

        val stored = fixture.repository.getFact(fact.id)
        assertNotNull(stored)
        assertEquals(MemoryFactStatus.DELETED, stored.status)
    }

    @Test
    fun `explicit remember accepts lower confidence candidate`() = runTest {
        val fixture = createFixture(
            writer = FixedWriter(
                listOf(
                    MemoryFactCandidate(
                        shouldSave = true,
                        kind = MemoryFactKind.PREFERENCE,
                        title = "User prefers Kotlin",
                        body = "User wants Kotlin implementation.",
                        scope = null,
                        slotKey = "implementation_language",
                        confidence = 0.45f,
                        evidenceText = "запомни: хочу реализацию на Kotlin",
                    )
                )
            )
        )

        val created = fixture.captureService.captureAfterTurn(
            MemoryCaptureInput(
                scopes = listOf(globalScope()),
                primaryScope = globalScope(),
                userMessage = "Запомни: хочу реализацию на Kotlin",
                assistantMessage = "Принял",
                conversationId = "chat-1",
                userMessageId = "u-1",
                assistantMessageId = "a-1",
            )
        )

        assertEquals(1, created.size)
        assertEquals("writer", created.single().createdBy)
    }

    @Test
    fun `writer empty output creates no facts`() = runTest {
        val fixture = createFixture(writer = FixedWriter(emptyList()))

        val created = fixture.captureService.captureAfterTurn(
            MemoryCaptureInput(
                scopes = listOf(globalScope()),
                primaryScope = globalScope(),
                userMessage = "Сделай кратко",
                assistantMessage = "Ок",
                conversationId = "chat-1",
                userMessageId = "u-1",
                assistantMessageId = "a-1",
            )
        )

        assertTrue(created.isEmpty())
    }

    @Test
    fun `valid writer candidate creates fact with evidence`() = runTest {
        val fixture = createFixture(
            writer = FixedWriter(
                listOf(
                    MemoryFactCandidate(
                        shouldSave = true,
                        kind = MemoryFactKind.PROJECT_RULE,
                        title = "Write tests first",
                        body = "Implement features test-first in this project.",
                        scope = projectScope(),
                        slotKey = "test_first_rule",
                        confidence = 0.91f,
                        evidenceText = "Before implementing the feature, write tests first.",
                    )
                )
            )
        )

        val created = fixture.captureService.captureAfterTurn(
            MemoryCaptureInput(
                scopes = listOf(globalScope(), projectScope()),
                primaryScope = projectScope(),
                userMessage = "Перед началом пиши тесты.",
                assistantMessage = "Сделаю.",
                conversationId = "chat-1",
                userMessageId = "u-1",
                assistantMessageId = "a-1",
            )
        )

        val details = fixture.repository.getFactDetails(created.single().id)

        assertNotNull(details)
        assertEquals(1, created.size)
        assertEquals("writer", created.single().createdBy)
        assertEquals(
            "Before implementing the feature, write tests first.",
            details.evidence.single().evidence.evidenceText,
        )
        assertEquals("turn", details.evidence.single().sourceEvent.sourceType)
    }

    @Test
    fun `invalid writer candidate is ignored`() = runTest {
        val fixture = createFixture(
            writer = FixedWriter(
                listOf(
                    MemoryFactCandidate(
                        shouldSave = true,
                        kind = MemoryFactKind.PROJECT_RULE,
                        title = "Rule",
                        body = "Rule body",
                        scope = null,
                        slotKey = null,
                        confidence = 0.3f,
                        evidenceText = "too weak",
                    )
                )
            )
        )

        val created = fixture.captureService.captureAfterTurn(
            MemoryCaptureInput(
                scopes = listOf(globalScope()),
                primaryScope = globalScope(),
                userMessage = "Не забудь",
                assistantMessage = "Ок",
                conversationId = "chat-1",
                userMessageId = "u-1",
                assistantMessageId = "a-1",
            )
        )

        assertTrue(created.isEmpty())
    }

    @Test
    fun `slotKey replacement retires previous fact inside same scope`() = runTest {
        val fixture = createFixture(writer = ReplacementWriter(projectScope()))

        val first = fixture.captureService.captureAfterTurn(
            MemoryCaptureInput(
                scopes = listOf(projectScope()),
                primaryScope = projectScope(),
                userMessage = "Use Postgres for memory storage.",
                assistantMessage = "Saved",
                conversationId = "chat-1",
                userMessageId = "u-1",
                assistantMessageId = "a-1",
            )
        ).single()

        val second = fixture.captureService.captureAfterTurn(
            MemoryCaptureInput(
                scopes = listOf(projectScope()),
                primaryScope = projectScope(),
                userMessage = "Use SQLite for desktop memory storage.",
                assistantMessage = "Updated",
                conversationId = "chat-1",
                userMessageId = "u-2",
                assistantMessageId = "a-2",
            )
        ).single()

        val oldFact = fixture.repository.getFact(first.id)
        val newFact = fixture.repository.getFact(second.id)

        assertNotNull(oldFact)
        assertNotNull(newFact)
        assertEquals(MemoryFactStatus.RETIRED, oldFact.status)
        assertEquals(MemoryFactStatus.ACTIVE, newFact.status)
        assertEquals(first.id, newFact.supersedesFactId)
    }

    @Test
    fun `same slotKey in different scopes does not affect other scope`() = runTest {
        val fixture = createFixture(
            writer = FixedWriter(
                listOf(
                    MemoryFactCandidate(
                        shouldSave = true,
                        kind = MemoryFactKind.PROJECT_DECISION,
                        title = "Chat storage",
                        body = "Use Postgres in this chat only.",
                        scope = chatScope("chat-2"),
                        slotKey = "memory_storage_target",
                        confidence = 0.95f,
                        evidenceText = "Use Postgres in this chat only.",
                    )
                )
            )
        )

        val projectFact = fixture.memoryService.createCapturedFact(
            CreateCapturedFactInput(
                scope = projectScope(),
                kind = MemoryFactKind.PROJECT_DECISION,
                title = "Project storage",
                body = "Use SQLite in the project scope.",
                slotKey = "memory_storage_target",
                confidence = 0.9f,
                evidenceText = "project evidence",
                sourceEventId = fixture.memoryService.saveTurnSourceEvent(
                    MemoryCaptureInput(
                        scopes = listOf(projectScope()),
                        primaryScope = projectScope(),
                        userMessage = "Use SQLite in project",
                        assistantMessage = "Saved",
                        conversationId = "chat-project",
                        userMessageId = "u-project",
                        assistantMessageId = "a-project",
                    )
                ),
            )
        )

        val created = fixture.captureService.captureAfterTurn(
            MemoryCaptureInput(
                scopes = listOf(chatScope("chat-2")),
                primaryScope = chatScope("chat-2"),
                userMessage = "Use Postgres in this chat only.",
                assistantMessage = "Saved",
                conversationId = "chat-2",
                userMessageId = "u-2",
                assistantMessageId = "a-2",
            )
        ).single()

        val storedProjectFact = fixture.repository.getFact(projectFact.id)
        val storedChatFact = fixture.repository.getFact(created.id)

        assertNotNull(storedProjectFact)
        assertNotNull(storedChatFact)
        assertEquals(MemoryFactStatus.ACTIVE, storedProjectFact.status)
        assertEquals(MemoryFactStatus.ACTIVE, storedChatFact.status)
        assertFalse(storedChatFact.supersedesFactId == projectFact.id)
    }

    @Test
    fun `retrieveForPrompt returns only active facts`() = runTest {
        val fixture = createFixture()
        val active = fixture.memoryService.createManualFact(
            CreateMemoryFactInput(
                scope = globalScope(),
                kind = MemoryFactKind.PREFERENCE,
                title = "Use Kotlin",
                body = "User prefers Kotlin implementation.",
            )
        )
        val retired = fixture.memoryService.createManualFact(
            CreateMemoryFactInput(
                scope = globalScope(),
                kind = MemoryFactKind.PREFERENCE,
                title = "Use Java",
                body = "Old preference for Java.",
            )
        )
        fixture.memoryService.retireFact(retired.id)

        val block = fixture.memoryService.retrieveForPrompt(
            scopes = listOf(globalScope()),
            query = "kotlin implementation",
            limit = 5,
        )

        assertEquals(listOf(active.id), block.facts.map { it.id })
    }

    @Test
    fun `retrieveForPrompt filters by scope`() = runTest {
        val fixture = createFixture()
        val global = fixture.memoryService.createManualFact(
            CreateMemoryFactInput(
                scope = globalScope(),
                kind = MemoryFactKind.PROCEDURE,
                title = "Global Kotlin rule",
                body = "Prefer Kotlin.",
            )
        )
        fixture.memoryService.createManualFact(
            CreateMemoryFactInput(
                scope = chatScope("chat-foreign"),
                kind = MemoryFactKind.PROCEDURE,
                title = "Foreign chat Python rule",
                body = "Prefer Python.",
            )
        )

        val block = fixture.memoryService.retrieveForPrompt(
            scopes = listOf(globalScope(), chatScope("chat-1")),
            query = "kotlin rule",
            limit = 5,
        )

        assertEquals(listOf(global.id), block.facts.map { it.id })
    }

    @Test
    fun `searchFacts sorts hits by cosine score`() = runTest {
        val fixture = createFixture()
        val strong = fixture.memoryService.createManualFact(
            CreateMemoryFactInput(
                scope = globalScope(),
                kind = MemoryFactKind.SEMANTIC,
                title = "SQLite memory",
                body = "SQLite desktop memory Kotlin.",
            )
        )
        val weaker = fixture.memoryService.createManualFact(
            CreateMemoryFactInput(
                scope = globalScope(),
                kind = MemoryFactKind.SEMANTIC,
                title = "Postgres memory",
                body = "Postgres backend memory.",
            )
        )

        val hits = fixture.repository.searchFacts(
            scopes = listOf(globalScope()),
            queryEmbedding = fixture.embedder.embedQuery("sqlite kotlin desktop"),
            limit = 5,
        )

        assertEquals(listOf(strong.id, weaker.id), hits.map { it.fact.id })
        assertTrue(hits[0].score >= hits[1].score)
    }

    @Test
    fun `memory block renderer returns compact block`() {
        val rendered = MemoryBlockRenderer.render(
            facts = listOf(
                MemoryFact(
                    id = "fact-1",
                    scope = globalScope(),
                    kind = MemoryFactKind.PROJECT_RULE,
                    title = "Write tests first",
                    body = "Always add tests before implementation.",
                    slotKey = null,
                    status = MemoryFactStatus.ACTIVE,
                    confidence = 0.9f,
                    pinned = false,
                    createdBy = "writer",
                    createdAt = java.time.Instant.EPOCH,
                    updatedAt = java.time.Instant.EPOCH,
                    supersedesFactId = null,
                )
            )
        )

        assertEquals(
            "Relevant memory:\n- [project_rule] Write tests first: Always add tests before implementation.",
            rendered,
        )
    }

    @Test
    fun `float array blob round trip preserves values`() {
        val original = floatArrayOf(0.25f, -1.5f, 9.75f)

        val restored = original.toBlob().toFloatArray()

        assertEquals(original.size, restored.size)
        original.indices.forEach { index ->
            assertTrue(abs(original[index] - restored[index]) < 0.0001f)
        }
        assertContentEquals(original.toList(), restored.toList())
    }

    private fun createFixture(
        writer: MemoryWriter = FixedWriter(emptyList()),
    ): Fixture {
        val dbDir = Files.createTempDirectory("souz-memory-test-")
        val repository = SqliteMemoryRepository(dbDir.resolve("memory.db"))
        val embedder = FakeEmbeddingClient()
        val memoryService = MemoryService(repository, embedder)
        return Fixture(
            repository = repository,
            embedder = embedder,
            memoryService = memoryService,
            captureService = MemoryCaptureService(memoryService, writer),
        )
    }

    private data class Fixture(
        val repository: MemoryRepository,
        val embedder: FakeEmbeddingClient,
        val memoryService: MemoryService,
        val captureService: MemoryCaptureService,
    )

    private class FakeEmbeddingClient : EmbeddingClient {
        override val model: String = "fake-embedding-v1"

        override suspend fun embedQuery(text: String): FloatArray = embed(text)

        override suspend fun embedDocument(text: String): FloatArray = embed(text)

        private fun embed(text: String): FloatArray {
            val normalized = text.lowercase()
            return floatArrayOf(
                keywordScore(normalized, "sqlite"),
                keywordScore(normalized, "postgres"),
                keywordScore(normalized, "kotlin"),
                keywordScore(normalized, "python"),
                keywordScore(normalized, "memory"),
                keywordScore(normalized, "desktop"),
                keywordScore(normalized, "project"),
                keywordScore(normalized, "chat"),
                keywordScore(normalized, "rule"),
                keywordScore(normalized, "dark"),
                keywordScore(normalized, "theme"),
                keywordScore(normalized, "dashboard"),
            )
        }

        private fun keywordScore(text: String, keyword: String): Float =
            if (text.contains(keyword)) 1f else 0f
    }

    private class FixedWriter(
        private val candidates: List<MemoryFactCandidate>,
    ) : MemoryWriter {
        override suspend fun extractCandidates(input: MemoryCaptureInput): List<MemoryFactCandidate> = candidates
    }

    private class ReplacementWriter(
        private val scope: MemoryScope,
    ) : MemoryWriter {
        override suspend fun extractCandidates(input: MemoryCaptureInput): List<MemoryFactCandidate> =
            listOf(
                MemoryFactCandidate(
                    shouldSave = true,
                    kind = MemoryFactKind.PROJECT_DECISION,
                    title = "Memory storage target",
                    body = input.userMessage,
                    scope = scope,
                    slotKey = "memory_storage_target",
                    confidence = 0.95f,
                    evidenceText = input.userMessage,
                )
            )
    }

    private fun globalScope(): MemoryScope = MemoryScope(type = "global", id = "global")

    private fun projectScope(): MemoryScope = MemoryScope(type = "project", id = "souz")

    private fun chatScope(id: String): MemoryScope = MemoryScope(type = "chat", id = id)

    private fun seedLegacyMemoryFactsSchema(dbPath: java.nio.file.Path) {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    create table memory_facts (
                        id text primary key,
                        slot_key text not null,
                        text text not null,
                        category text not null,
                        status text not null,
                        confidence real not null,
                        created_at integer not null,
                        updated_at integer not null,
                        last_seen_at integer not null,
                        last_confirmed_at integer not null,
                        source_count integer not null default 0,
                        superseded_by text,
                        embedding_json text not null default '[]'
                    )
                    """.trimIndent()
                )
                statement.execute(
                    """
                    create table memory_fact_evidence (
                        id text primary key,
                        fact_id text not null,
                        evidence_id text not null,
                        support_type text not null,
                        weight real not null,
                        created_at text not null
                    )
                    """.trimIndent()
                )
            }
            connection.prepareStatement(
                """
                insert into memory_facts(
                    id, slot_key, text, category, status, confidence,
                    created_at, updated_at, last_seen_at, last_confirmed_at, source_count, superseded_by, embedding_json
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, "legacy-fact-1")
                statement.setString(2, "user.preference.theme")
                statement.setString(3, "User prefers concise dark theme dashboards.")
                statement.setString(4, "PREFERENCE")
                statement.setString(5, "ACTIVE")
                statement.setFloat(6, 0.93f)
                statement.setLong(7, 1_717_000_000_000L)
                statement.setLong(8, 1_717_000_100_000L)
                statement.setLong(9, 1_717_000_100_000L)
                statement.setLong(10, 1_717_000_100_000L)
                statement.setInt(11, 1)
                statement.setString(12, null)
                statement.setString(13, "[]")
                statement.executeUpdate()
            }
        }
    }
}
