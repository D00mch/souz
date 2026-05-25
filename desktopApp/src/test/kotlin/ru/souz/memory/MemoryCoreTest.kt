@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package ru.souz.memory

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MemoryCoreTest {
    @Test
    fun `createManualFact stores source event fact evidence and embedding`() = runTest {
        val fixture = createFixture()

        val fact = fixture.createManual(
            scope = projectScope(),
            kind = MemoryFactKind.PROJECT_RULE,
            title = "Use SQLite first",
            body = "Memory should be implemented on desktop first with SQLite.",
        )

        val details = fixture.repository.getFactDetails(fact.id)
        val hits = fixture.search(projectScope(), "sqlite desktop memory")

        assertNotNull(details)
        assertEquals(MemoryFactStatus.ACTIVE, details.fact.status)
        assertEquals("user", details.fact.createdBy)
        assertEquals(1, details.evidence.size)
        assertEquals("manual", details.evidence.single().sourceEvent.sourceType)
        assertTrue(details.evidence.single().sourceEvent.text.contains("Use SQLite first"))
        assertEquals(listOf(fact.id), hits.map { it.fact.id })
        assertEquals(1, fixture.embedder.documentCallCount)
    }

    @Test
    fun `updateFact updates text and recalculates embedding`() = runTest {
        val fixture = createFixture()
        val fact = fixture.createManual(
            scope = projectScope(),
            kind = MemoryFactKind.PROJECT_DECISION,
            title = "Initial storage",
            body = "Use Postgres for memory storage.",
        )

        val updated = fixture.memoryService.updateFact(
            factId = fact.id,
            patch = MemoryFactPatch(
                title = "Desktop storage",
                body = "Use SQLite for desktop memory storage.",
            )
        )

        val hits = fixture.search(projectScope(), "sqlite desktop memory")

        assertEquals("Desktop storage", updated.title)
        assertEquals("Use SQLite for desktop memory storage.", updated.body)
        assertEquals(fact.id, hits.first().fact.id)
        assertTrue(hits.first().score > 0.5f)
        assertEquals(2, fixture.embedder.documentCallCount)
    }

    @Test
    fun `updateFact moves fact between scopes`() = runTest {
        val fixture = createFixture()
        val fact = fixture.createManual(
            scope = projectScope(),
            kind = MemoryFactKind.PROJECT_DECISION,
            title = "Scope target",
            body = "This fact should move to chat scope.",
        )

        val updated = fixture.memoryService.updateFact(
            factId = fact.id,
            patch = MemoryFactPatch(scope = chatScope("chat-7")),
        )

        assertEquals(chatScope("chat-7"), updated.scope)
        assertTrue(fixture.repository.listFacts(MemoryFactFilter(scope = projectScope())).none { it.id == fact.id })
        assertEquals(
            listOf(fact.id),
            fixture.repository.listFacts(MemoryFactFilter(scope = chatScope("chat-7"))).map { it.id },
        )
    }

    @Test
    fun `capture creates valid fact with evidence and explicit remember accepts lower confidence`() = runTest {
        val fixture = createFixture(
            writer = FixedWriter(
                candidate(
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

        val created = fixture.capture(scopes = listOf(globalScope(), projectScope()))
        val details = fixture.repository.getFactDetails(created.single().id)

        assertNotNull(details)
        assertEquals("writer", created.single().createdBy)
        assertEquals("Before implementing the feature, write tests first.", details.evidence.single().evidence.evidenceText)
        assertEquals("turn", details.evidence.single().sourceEvent.sourceType)

        val explicitRememberFixture = createFixture(
            writer = FixedWriter(
                candidate(
                    kind = MemoryFactKind.PREFERENCE,
                    title = "User prefers Kotlin",
                    body = "User wants Kotlin implementation.",
                    slotKey = "implementation_language",
                    confidence = 0.45f,
                    evidenceText = "запомни: хочу реализацию на Kotlin",
                )
            )
        )
        val remembered = explicitRememberFixture.capture(
            userMessage = "Запомни: хочу реализацию на Kotlin",
        )

        assertEquals(1, remembered.size)
        assertEquals("writer", remembered.single().createdBy)
    }

    @Test
    fun `invalid writer candidate is ignored`() = runTest {
        val fixture = createFixture(
            writer = FixedWriter(
                candidate(
                    kind = MemoryFactKind.PROJECT_RULE,
                    title = "Rule",
                    body = "Rule body",
                    confidence = 0.3f,
                    evidenceText = "too weak",
                )
            )
        )

        val created = fixture.capture(userMessage = "Не забудь")

        assertTrue(created.isEmpty())
    }

    @Test
    fun `slotKey replacement retires previous fact inside same scope`() = runTest {
        val fixture = createFixture(writer = ReplacementWriter(projectScope()))

        val first = fixture.capture(
            userMessage = "Use Postgres for memory storage.",
            primaryScope = projectScope(),
            scopes = listOf(projectScope()),
        ).single()
        val second = fixture.capture(
            userMessage = "Use SQLite for desktop memory storage.",
            primaryScope = projectScope(),
            scopes = listOf(projectScope()),
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
                candidate(
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
        val projectSourceId = fixture.memoryService.saveRedactedSourceEvent(
            memoryCapture(primaryScope = projectScope(), scopes = listOf(projectScope())),
            "project evidence"
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
                sourceEventId = projectSourceId,
            )
        )

        val chatFact = fixture.capture(primaryScope = chatScope("chat-2"), scopes = listOf(chatScope("chat-2"))).single()

        assertEquals(MemoryFactStatus.ACTIVE, fixture.repository.getFact(projectFact.id)?.status)
        assertEquals(MemoryFactStatus.ACTIVE, fixture.repository.getFact(chatFact.id)?.status)
        assertFalse(fixture.repository.getFact(chatFact.id)?.supersedesFactId == projectFact.id)
    }

    @Test
    fun `capture normalizes malformed writer scope ids`() = runTest {
        val fixture = createFixture(
            writer = FixedWriter(
                candidate(
                    kind = MemoryFactKind.SEMANTIC,
                    title = "Primary career goal: Anthropic",
                    body = "User wants to work at Anthropic.",
                    scope = MemoryScope("global", "global:global"),
                    slotKey = "career_goal_anthropic",
                    confidence = 0.95f,
                    evidenceText = "My primary career goal is Anthropic.",
                )
            )
        )

        val created = fixture.capture(
            userMessage = "Запомни: моя главная карьерная цель - Anthropic",
        ).single()

        assertEquals(globalScope(), created.scope)
        assertEquals(listOf(created.id), fixture.search(globalScope(), "anthropic career goal").map { it.fact.id })
    }

    @Test
    fun `retrieveForPrompt returns only active facts from requested scopes`() = runTest {
        val fixture = createFixture()
        val active = fixture.createManual(
            kind = MemoryFactKind.PREFERENCE,
            title = "Use Kotlin",
            body = "User prefers Kotlin implementation.",
        )
        val retired = fixture.createManual(
            kind = MemoryFactKind.PREFERENCE,
            title = "Old Kotlin preference",
            body = "Old preference for Kotlin scripts.",
        )
        fixture.memoryService.retireFact(retired.id)
        fixture.createManual(
            scope = chatScope("foreign"),
            kind = MemoryFactKind.PREFERENCE,
            title = "Foreign Kotlin preference",
            body = "Foreign chat also mentions Kotlin.",
        )

        val block = fixture.memoryService.retrieveForPrompt(
            scopes = listOf(globalScope(), chatScope("current")),
            query = "kotlin implementation",
            limit = 5,
        )

        assertEquals(listOf(active.id), block.facts.map { it.id })
    }

    @Test
    fun `retrieveForPrompt prepends pinned facts includes legacy scopes and keeps five facts`() = runTest {
        val fixture = createFixture()
        val pinnedPreference = fixture.createManual(
            title = "User Language",
            body = "Respond in Russian.",
            kind = MemoryFactKind.PREFERENCE,
            pinned = true,
        )
        val pinnedLegacy = fixture.createLegacy(
            title = "Primary career goal: Anthropic",
            body = "User wants to work at Anthropic.",
            scope = legacyGlobalScope(),
            kind = MemoryFactKind.SEMANTIC,
            pinned = true,
        )
        val strong = fixture.createManual(title = "SQLite memory", body = "SQLite desktop memory Kotlin.")
        val medium = fixture.createManual(title = "Kotlin desktop", body = "Kotlin desktop memory project.")
        val weak = fixture.createManual(title = "Chat memory", body = "Chat memory Kotlin.")
        fixture.createManual(title = "Python notes", body = "Python project.")

        val block = fixture.memoryService.retrieveForPrompt(
            scopes = listOf(globalScope()),
            query = "kotlin desktop memory",
        )

        assertEquals(5, block.facts.size)
        assertEquals(setOf(pinnedPreference.id, pinnedLegacy.id), block.facts.take(2).map { it.id }.toSet())
        assertEquals(listOf(strong.id, medium.id, weak.id), block.facts.drop(2).map { it.id })
    }

    @Test
    fun `retrieveForPrompt lazily backfills facts without embeddings in hot path`() = runTest {
        val fixture = createFixture()
        val sourceId = fixture.repository.insertSourceEvent(
            NewMemorySourceEvent(
                scope = globalScope(),
                sourceType = "test",
                sourceRef = null,
                text = "User prefers Kotlin.",
            )
        )
        val factId = fixture.repository.insertFact(
            NewMemoryFact(
                scope = globalScope(),
                kind = MemoryFactKind.PREFERENCE,
                title = "Use Kotlin",
                body = "User prefers Kotlin implementation.",
                slotKey = null,
                status = MemoryFactStatus.ACTIVE,
                confidence = 1f,
                pinned = false,
                createdBy = "user",
                supersedesFactId = null,
            ),
            evidence = listOf(MemoryEvidenceRef(sourceId, "User prefers Kotlin.")),
        )
        fixture.embedder.resetCounts()

        val block = fixture.memoryService.retrieveForPrompt(
            scopes = listOf(globalScope()),
            query = "kotlin implementation",
            limit = 5,
        )

        assertEquals(1, block.facts.size)
        assertEquals(factId, block.facts.first().id)
        assertEquals(1, fixture.embedder.queryCallCount)
        assertEquals(1, fixture.embedder.documentCallCount)
    }

    @Test
    fun `retireFact and deleteFact mark status`() = runTest {
        val fixture = createFixture()
        val retired = fixture.createManual(title = "Retired fact")
        val deleted = fixture.createManual(title = "Deleted fact")

        fixture.memoryService.retireFact(retired.id)
        fixture.memoryService.deleteFact(deleted.id)

        assertEquals(MemoryFactStatus.RETIRED, fixture.repository.getFact(retired.id)?.status)
        assertEquals(null, fixture.repository.getFact(deleted.id))
    }

    @Test
    fun `searchFacts sorts hits by cosine score`() = runTest {
        val fixture = createFixture()
        val strong = fixture.createManual(
            title = "SQLite memory",
            body = "SQLite desktop memory Kotlin.",
        )
        val weaker = fixture.createManual(
            title = "Postgres memory",
            body = "Postgres backend memory.",
        )

        val hits = fixture.search(globalScope(), "sqlite kotlin desktop")

        assertEquals(listOf(strong.id, weaker.id), hits.map { it.fact.id })
        assertTrue(hits[0].score >= hits[1].score)
    }

    private fun createFixture(
        writer: MemoryWriter = FixedWriter(),
    ): Fixture = SqliteMemoryRepository(Files.createTempDirectory("souz-memory-test-").resolve("memory.db"))
        .let { repository ->
            FakeEmbeddingClient().let { embedder ->
                MemoryService(repository, embedder).let { service ->
                    Fixture(repository, embedder, service, MemoryCaptureService(service, writer))
                }
            }
        }

    private data class Fixture(
        val repository: MemoryRepository,
        val embedder: FakeEmbeddingClient,
        val memoryService: MemoryService,
        val captureService: MemoryCaptureService,
    )

    private suspend fun Fixture.createManual(
        title: String,
        body: String = "Souz is a desktop app.",
        scope: MemoryScope = globalScope(),
        kind: MemoryFactKind = MemoryFactKind.SEMANTIC,
        pinned: Boolean = false,
    ): MemoryFact = memoryService.createManualFact(
        CreateMemoryFactInput(
            scope = scope,
            kind = kind,
            title = title,
            body = body,
            pinned = pinned,
        )
    )

    private suspend fun Fixture.createLegacy(
        title: String,
        body: String,
        scope: MemoryScope,
        kind: MemoryFactKind,
        pinned: Boolean = false,
    ): MemoryFact {
        val sourceId = repository.insertSourceEvent(
            NewMemorySourceEvent(
                scope = scope,
                sourceType = "manual",
                sourceRef = null,
                text = body,
            )
        )
        val factId = repository.insertFact(
            NewMemoryFact(
                scope = scope,
                kind = kind,
                title = title,
                body = body,
                slotKey = null,
                status = MemoryFactStatus.ACTIVE,
                confidence = 1f,
                pinned = pinned,
                createdBy = "writer",
                supersedesFactId = null,
            ),
            evidence = listOf(MemoryEvidenceRef(sourceId, body)),
        )
        return repository.getFact(factId) ?: error("Legacy fact not found: $factId")
    }

    private suspend fun Fixture.capture(
        userMessage: String = "Перед началом пиши тесты.",
        assistantMessage: String = "Сделаю.",
        primaryScope: MemoryScope = globalScope(),
        scopes: List<MemoryScope> = listOf(globalScope()),
    ): List<MemoryFact> =
        captureService.captureAfterTurn(memoryCapture(userMessage, assistantMessage, primaryScope, scopes))

    private suspend fun Fixture.search(
        scope: MemoryScope,
        query: String,
    ): List<MemoryFactSearchHit> =
        repository.searchFacts(
            scopes = listOf(scope),
            model = embedder.model,
            queryEmbedding = embedder.embedQuery(query),
            limit = 5,
        )

    private class FakeEmbeddingClient : EmbeddingClient {
        override val model: String = "fake-embedding-v1"
        var queryCallCount = 0
            private set
        var documentCallCount = 0
            private set

        override suspend fun embedQuery(text: String): FloatArray {
            queryCallCount++
            return embed(text)
        }

        override suspend fun embedDocument(text: String): FloatArray {
            documentCallCount++
            return embed(text)
        }

        fun resetCounts() {
            queryCallCount = 0
            documentCallCount = 0
        }

        private fun embed(text: String): FloatArray =
            keywords.map { keyword -> if (text.lowercase().contains(keyword)) 1f else 0f }.toFloatArray()

        private val keywords = listOf("sqlite", "postgres", "kotlin", "python", "memory", "desktop", "project", "chat", "rule")
    }

    private class FixedWriter(private vararg val candidates: MemoryFactCandidate) : MemoryWriter {
        override suspend fun extractCandidates(input: MemoryCaptureInput): List<MemoryFactCandidate> =
            candidates.toList()
    }

    private class ReplacementWriter(private val scope: MemoryScope) : MemoryWriter {
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

    private fun candidate(
        kind: MemoryFactKind,
        title: String,
        body: String,
        scope: MemoryScope? = null,
        slotKey: String? = null,
        confidence: Float = 0.9f,
        evidenceText: String = body,
    ): MemoryFactCandidate =
        MemoryFactCandidate(
            shouldSave = true,
            kind = kind,
            title = title,
            body = body,
            scope = scope,
            slotKey = slotKey,
            confidence = confidence,
            evidenceText = evidenceText,
        )

    private fun memoryCapture(
        userMessage: String = "Перед началом пиши тесты.",
        assistantMessage: String = "Сделаю.",
        primaryScope: MemoryScope = globalScope(),
        scopes: List<MemoryScope> = listOf(globalScope()),
    ): MemoryCaptureInput =
        MemoryCaptureInput(
            scopes = scopes,
            primaryScope = primaryScope,
            userMessage = userMessage,
            assistantMessage = assistantMessage,
            conversationId = "chat-1",
            userMessageId = "u-1",
            assistantMessageId = "a-1",
        )

    private fun globalScope(): MemoryScope = MemoryScope(type = "global", id = "global")

    private fun legacyGlobalScope(): MemoryScope = MemoryScope(type = "global", id = "global:global")

    private fun projectScope(): MemoryScope = MemoryScope(type = "project", id = "souz")

    private fun chatScope(id: String): MemoryScope = MemoryScope(type = "chat", id = id)
}
