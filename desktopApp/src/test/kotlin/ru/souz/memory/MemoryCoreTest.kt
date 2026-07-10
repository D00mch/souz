@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package ru.souz.memory

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ru.souz.ui.main.usecases.MemoryServiceConversationCleanup
import ru.souz.llms.LLMModel

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
    fun `updateFact replacing canonical key retires previous active fact`() = runTest {
        val fixture = createFixture()
        val oldFact = fixture.memoryService.createManualFact(
            CreateMemoryFactInput(
                ownerId = fixture.owner,
                scope = projectScope(),
                kind = MemoryFactKind.PROJECT_DECISION,
                title = "Old storage",
                body = "Use Postgres for memory storage.",
                canonicalKey = "project.decision.memory.storage.target",
            )
        )
        val editedFact = fixture.memoryService.createManualFact(
            CreateMemoryFactInput(
                ownerId = fixture.owner,
                scope = projectScope(),
                kind = MemoryFactKind.PROJECT_DECISION,
                title = "Edited storage",
                body = "Use SQLite for memory storage.",
                canonicalKey = "project.decision.memory.storage.edited",
            )
        )

        val updated = fixture.memoryService.updateFact(
            factId = editedFact.id,
            patch = MemoryFactPatch(canonicalKey = "project.decision.memory.storage.target"),
        )

        val activeFacts = fixture.repository.listFacts(MemoryFactFilter(scope = projectScope()))

        assertEquals("project.decision.memory.storage.target", updated.canonicalKey)
        assertEquals(oldFact.id, updated.supersedesFactId)
        assertEquals(MemoryFactStatus.RETIRED, fixture.repository.getFact(oldFact.id)?.status)
        assertEquals(MemoryFactStatus.ACTIVE, fixture.repository.getFact(editedFact.id)?.status)
        assertEquals(listOf(editedFact.id), activeFacts.map { it.id })
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
                    slotKey = "project.rule.test.first",
                    confidence = 0.91f,
                    evidenceText = "Before implementing the feature, write tests first.",
                )
            )
        )

        val created = fixture.capture(
            userMessage = "Before implementing the feature, write tests first.",
            scopes = listOf(globalScope(), projectScope()),
        )
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
                    slotKey = "user.preference.implementation.language",
                    confidence = 0.45f,
                    evidenceText = "запомни: хочу реализацию на Kotlin",
                )
            )
        )
        val remembered = explicitRememberFixture.capture(
            userMessage = "Запомни: хочу реализацию на Kotlin",
        )

        assertTrue(remembered.any { it.title == "User prefers Kotlin" && it.createdBy == "writer" })
    }

    @Test
    fun `capture stores one grounded turn event for multiple facts`() = runTest {
        val fixture = createFixture(
            writer = FixedWriter(
                candidate(
                    kind = MemoryFactKind.SEMANTIC,
                    title = "Project ownership",
                    body = "The user owns project Alpha.",
                    requestedScope = RequestedMemoryScope.GLOBAL,
                    evidenceText = "I own project Alpha.",
                ),
                candidate(
                    kind = MemoryFactKind.PROJECT_DECISION,
                    title = "Project storage",
                    body = "Project Alpha uses SQLite.",
                    scope = projectScope(),
                    evidenceText = "Project Alpha uses SQLite.",
                ),
            )
        )
        val input = memoryCapture(
            userMessage = "I own project Alpha.",
            primaryScope = projectScope(),
            scopes = listOf(globalScope(), projectScope()),
        ).copy(
            evidence = listOf(
                CompletedTurnEvidence(
                    kind = CompletedTurnEvidenceKind.TOOL_OUTPUT,
                    sourceName = "ProjectInspector",
                    text = "Project Alpha uses SQLite.",
                )
            )
        )

        val created = fixture.captureService.captureAfterTurn(input)
        val details = created.map { fixture.repository.getFactDetails(it.id) ?: error("missing details") }

        assertEquals(2, created.size)
        assertEquals(1, details.flatMap { it.evidence }.map { it.sourceEvent.id }.distinct().size)
        val sourceText = details.first().evidence.single().sourceEvent.text
        assertTrue(sourceText.contains("[USER]"))
        assertTrue(sourceText.contains("I own project Alpha."))
        assertTrue(sourceText.contains("[TOOL_OUTPUT source=ProjectInspector]"))
        assertTrue(sourceText.contains("Project Alpha uses SQLite."))
        assertEquals(
            setOf("I own project Alpha.", "Project Alpha uses SQLite."),
            details.map { it.evidence.single().evidence.evidenceText }.toSet(),
        )
        assertEquals(1, countRows(fixture.dbPath, "memory_source_events", "source_type = 'turn'"))
    }

    @Test
    fun `capture grounds durable facts in user or tool evidence and synthesis only in session episodes`() = runTest {
        val sessionScope = MemoryScope.session(MemorySessionId("chat-1"))
        val fixture = createFixture(
            writer = FixedWriter(
                candidate(
                    kind = MemoryFactKind.SEMANTIC,
                    title = "Assistant claim",
                    body = "Assistant-only global claim.",
                    requestedScope = RequestedMemoryScope.GLOBAL,
                    evidenceText = "Assistant-only global claim.",
                ),
                candidate(
                    kind = MemoryFactKind.EPISODE_NOTE,
                    title = "Working state",
                    body = "Assistant-only working state.",
                    scope = sessionScope,
                    evidenceText = "Assistant-only working state.",
                ),
                candidate(
                    kind = MemoryFactKind.SEMANTIC,
                    title = "Fabricated claim",
                    body = "This was never observed.",
                    requestedScope = RequestedMemoryScope.GLOBAL,
                    evidenceText = "This was never observed.",
                ),
            )
        )
        val input = memoryCapture(
            userMessage = "Continue the task.",
            primaryScope = sessionScope,
            scopes = listOf(globalScope(), sessionScope),
        ).copy(
            evidence = listOf(
                CompletedTurnEvidence(CompletedTurnEvidenceKind.ASSISTANT_SYNTHESIS, text = "Assistant-only global claim."),
                CompletedTurnEvidence(CompletedTurnEvidenceKind.ASSISTANT_SYNTHESIS, text = "Assistant-only working state."),
            )
        )

        val created = fixture.captureService.captureAfterTurn(input)

        assertEquals(listOf("Working state"), created.map { it.title })
        assertEquals(sessionScope, created.single().scope)
        assertEquals(MemoryFactKind.EPISODE_NOTE, created.single().kind)
    }

    @Test
    fun `capture retries writer and avoids explicit fallback duplication`() = runTest {
        val candidate = candidate(
            kind = MemoryFactKind.SEMANTIC,
            title = "Concise replies",
            body = "The user prefers concise replies.",
            requestedScope = RequestedMemoryScope.GLOBAL,
            evidenceText = "I prefer concise replies.",
        )
        val retryingWriter = FlakyWriter(2, candidate)
        val retryFixture = createFixture(writer = retryingWriter)

        val retried = retryFixture.capture(userMessage = "I prefer concise replies.")

        assertEquals(3, retryingWriter.callCount)
        assertEquals(listOf("Concise replies"), retried.map { it.title })

        val explicitWriter = FixedWriter(candidate.copy(evidenceText = "Remember that I prefer concise replies."))
        val explicitFixture = createFixture(writer = explicitWriter)
        val explicit = explicitFixture.capture(userMessage = "Remember that I prefer concise replies.")

        assertEquals(1, explicit.size)
        assertEquals("Concise replies", explicit.single().title)
    }

    @Test
    fun `capture propagates implicit writer failure after three attempts`() = runTest {
        val writer = FlakyWriter(failures = Int.MAX_VALUE)
        val fixture = createFixture(writer = writer)

        assertFailsWith<MemoryWriterException> {
            fixture.capture(userMessage = "I prefer concise replies.")
        }
        assertEquals(3, writer.callCount)
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
                    slotKey = "project.decision.memory.storage.target",
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
                slotKey = "project.decision.memory.storage.target",
                confidence = 0.9f,
                evidenceText = "project evidence",
                sourceEventId = projectSourceId,
            )
        )

        val chatFact = fixture.capture(
            userMessage = "Use Postgres in this chat only.",
            primaryScope = chatScope("chat-2"),
            scopes = listOf(chatScope("chat-2")),
        ).single()

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
                    slotKey = "user.preference.career.goal.anthropic",
                    requestedScope = null,
                    confidence = 0.95f,
                    evidenceText = "My primary career goal is Anthropic.",
                )
            )
        )

        val created = fixture.capture(
            userMessage = "My primary career goal is Anthropic.",
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
    fun `migration queues legacy chat facts and excludes them from global retrieval`() = runTest {
        val dbPath = Files.createTempDirectory("souz-memory-v1-test-").resolve("memory.db")
        seedLegacyV1MemoryDb(dbPath)
        val repository = SqliteMemoryRepository(dbPath)
        val memoryService = MemoryService(repository, FakeEmbeddingClient())

        val migratedChatFacts = repository.listFacts(
            MemoryFactFilter(scope = MemoryScope("chat", "chat-legacy"))
        )
        val block = memoryService.retrieveForPrompt(
            scopes = listOf(globalScope()),
            query = "legacy kotlin chat memory",
            limit = 5,
        )

        assertEquals(listOf("fact-legacy-chat"), migratedChatFacts.map { it.id })
        assertTrue(block.facts.none { it.id == "fact-legacy-chat" })
        assertEquals(1, countRows(dbPath, "memory_maintenance_jobs"))
    }

    @Test
    fun `legacy owner migration moves owner columns to current desktop owner`() = runTest {
        val dbPath = Files.createTempDirectory("souz-memory-owner-test-").resolve("memory.db")
        seedLegacyV1MemoryDb(dbPath)
        val owner = MemoryOwnerId("desktop-owner")
        val repository = SqliteMemoryRepository(dbPath, legacyOwnerMigrationTarget = owner)

        val facts = repository.listFacts(MemoryFactFilter(ownerId = owner, scope = MemoryScope("chat", "chat-legacy")))

        assertEquals(listOf("fact-legacy-chat"), facts.map { it.id })
        assertEquals(0, countRows(dbPath, "memory_facts", "owner_id = 'local-legacy-owner'"))
        assertEquals(0, countRows(dbPath, "memory_source_events", "owner_id = 'local-legacy-owner'"))
        assertEquals(1, countRows(dbPath, "memory_maintenance_jobs", "owner_id = 'desktop-owner'"))
    }

    @Test
    fun `legacy slot key migration normalizes key and replacement supersedes migrated fact`() = runTest {
        val dbPath = Files.createTempDirectory("souz-memory-slot-key-test-").resolve("memory.db")
        seedLegacySlotKeyMemoryDb(
            dbPath = dbPath,
            factId = "fact-legacy-project",
            scope = projectScope(),
            slotKey = "project_decision_memory_storage_target",
        )
        val owner = MemoryOwnerId("desktop-owner")
        val repository = SqliteMemoryRepository(dbPath, legacyOwnerMigrationTarget = owner)
        val memoryService = MemoryService(repository, FakeEmbeddingClient())

        assertEquals("project.decision.memory.storage.target", repository.getFact("fact-legacy-project")?.canonicalKey)

        val sourceId = repository.insertSourceEvent(
            NewMemorySourceEvent(
                ownerId = owner,
                scope = projectScope(),
                sourceType = "turn",
                sourceRef = "assistant-2",
                text = "Use SQLite for desktop memory storage.",
            )
        )
        val replacement = memoryService.createCapturedFact(
            CreateCapturedFactInput(
                ownerId = owner,
                scope = projectScope(),
                kind = MemoryFactKind.PROJECT_DECISION,
                title = "Memory storage target",
                body = "Use SQLite for desktop memory storage.",
                canonicalKey = "project.decision.memory.storage.target",
                confidence = 0.95f,
                evidenceText = "Use SQLite for desktop memory storage.",
                sourceEventId = sourceId,
            )
        )

        assertEquals("fact-legacy-project", replacement.supersedesFactId)
        assertEquals(MemoryFactStatus.RETIRED, repository.getFact("fact-legacy-project")?.status)
        assertEquals(MemoryFactStatus.ACTIVE, repository.getFact(replacement.id)?.status)
    }

    @Test
    fun `retrieveForPrompt uses pinned as relevance boost and keeps bounded facts`() = runTest {
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

        assertEquals(3, block.facts.size)
        assertTrue(block.facts.none { it.id == pinnedPreference.id })
        assertTrue(block.facts.none { it.id == pinnedLegacy.id })
        assertEquals(setOf(strong.id, medium.id, weak.id), block.facts.map { it.id }.toSet())
    }

    @Test
    fun `retrieveForPrompt does not backfill facts without embeddings in hot path`() = runTest {
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
        assertEquals(0, fixture.embedder.documentCallCount)
    }

    @Test
    fun `retrieveForPrompt includes older pinned facts even when newer facts fill the page`() = runTest {
        val fixture = createFixture()
        val pinned = fixture.createManual(
            title = "Respond in Russian",
            body = "User prefers Russian.",
            kind = MemoryFactKind.PREFERENCE,
            pinned = true,
        )
        repeat(5) { index ->
            fixture.createManual(
                title = "Recent note $index",
                body = "Recent unpinned note $index.",
                kind = MemoryFactKind.SEMANTIC,
            )
        }

        val block = fixture.memoryService.retrieveForPrompt(
            scopes = listOf(globalScope()),
            query = "",
            limit = 5,
        )

        assertEquals(listOf(pinned.id), block.facts.map { it.id })
    }

    @Test
    fun `retireFact marks status and deleteFact removes row`() = runTest {
        val fixture = createFixture()
        val retired = fixture.createManual(title = "Retired fact")
        val deleted = fixture.createManual(title = "Deleted fact")
        fixture.memoryService.retrieveForPrompt(listOf(globalScope()), "Deleted fact", limit = 1)

        fixture.memoryService.retireFact(retired.id)
        fixture.memoryService.deleteFact(deleted.id)

        assertEquals(MemoryFactStatus.RETIRED, fixture.repository.getFact(retired.id)?.status)
        assertEquals(null, fixture.repository.getFact(deleted.id))
        assertEquals(0, fixture.countRows("memory_fact_stats", "fact_id = '${deleted.id}'"))
        assertEquals(0, fixture.countRows("memory_index_jobs", "fact_id = '${deleted.id}'"))
    }

    @Test
    fun `deleteFactsByScope removes session and legacy chat facts`() = runTest {
        val owner = MemoryOwnerId("desktop-owner")
        val fixture = createFixture(owner = owner)
        val sessionFact = fixture.createManual(scope = MemoryScope.session(MemorySessionId("chat-42")), title = "Session note")
        val legacyChatFact = fixture.createManual(scope = chatScope("chat-42"), title = "Legacy chat note")
        val globalFact = fixture.createManual(scope = globalScope(), title = "Global note")

        fixture.memoryService.deleteFactsByScope(owner, MemoryScope.session(MemorySessionId("chat-42")))
        fixture.memoryService.deleteFactsByScope(owner, chatScope("chat-42"))

        assertEquals(null, fixture.repository.getFact(sessionFact.id))
        assertEquals(null, fixture.repository.getFact(legacyChatFact.id))
        assertNotNull(fixture.repository.getFact(globalFact.id))
    }

    @Test
    fun `closed session scope rejects late writer insert in repository transaction`() = runTest {
        val fixture = createFixture(owner = MemoryOwnerId("desktop-owner"))
        val scope = MemoryScope.session(MemorySessionId("chat-42"))

        fixture.memoryService.closeScopeForCapture(fixture.owner, scope)

        assertFailsWith<MemoryScopeClosedForCaptureException> {
            fixture.repository.insertFact(
                NewMemoryFact(
                    ownerId = fixture.owner,
                    scope = scope,
                    kind = MemoryFactKind.SEMANTIC,
                    title = "Late session note",
                    body = "This should not be stored after cleanup.",
                    slotKey = null,
                    status = MemoryFactStatus.ACTIVE,
                    confidence = 1f,
                    pinned = false,
                    createdBy = "writer",
                    supersedesFactId = null,
                ),
                evidence = emptyList(),
            )
        }
    }

    @Test
    fun `cleanup closing conversation prevents in flight capture from recreating session facts`() = runTest {
        val owner = MemoryOwnerId("desktop-owner")
        val conversationId = "chat-42"
        val sessionScope = MemoryScope.session(MemorySessionId(conversationId))
        val writer = BlockingWriter(
            candidate(
                kind = MemoryFactKind.SEMANTIC,
                title = "Late session note",
                body = "This should not be stored after cleanup.",
                scope = sessionScope,
                confidence = 0.9f,
                evidenceText = "This should not be stored after cleanup.",
            )
        )
        val fixture = createFixture(writer = writer, owner = owner)
        val cleanup = MemoryServiceConversationCleanup(
            memoryService = fixture.memoryService,
            ownerProvider = MemoryOwnerProvider { owner },
        )
        val capture = async {
            fixture.captureService.captureAfterTurn(
                MemoryCaptureInput(
                    context = MemoryContext(
                        ownerId = owner,
                        conversationId = ConversationId(conversationId),
                        sessionId = MemorySessionId(conversationId),
                        projectId = null,
                    ),
                    scopes = listOf(sessionScope),
                    userMessage = "Remember a session-only note.",
                    assistantMessage = "Saved.",
                    conversationId = conversationId,
                    userMessageId = "u-1",
                    assistantMessageId = "a-1",
                )
            )
        }
        writer.firstCallEntered.await()

        cleanup.cleanupConversation(conversationId)
        writer.releaseFirstCall.complete(Unit)

        assertTrue(capture.await().isEmpty())
        assertEquals(
            0,
            countRows(
                fixture.dbPath,
                "memory_facts",
                "owner_id = '${owner.value}' and scope_type in ('session', 'chat') and scope_id = '$conversationId'",
            )
        )
        assertEquals(
            0,
            countRows(
                fixture.dbPath,
                "memory_source_events",
                "owner_id = '${owner.value}' and scope_type = 'session' and scope_id = '$conversationId'",
            )
        )
    }

    @Test
    fun `disabling dreamer clears stale maintenance error`() = runTest {
        val dbPath = Files.createTempDirectory("souz-memory-disabled-test-").resolve("memory.db")
        val settings = InMemoryMemoryMaintenanceSettingsStore().apply {
            put("MEMORY_MAINTENANCE_LAST_ERROR_CODE", "IllegalStateException")
        }
        val controller = DesktopMemoryMaintenanceController(dbPath, settings)

        val status = controller.savePreferences(MemoryMaintenancePreferences(mode = MemoryMaintenanceMode.OFF))

        assertNull(status.lastErrorCode)
        assertEquals(MemoryMaintenanceBlockReason.DREAMER_DISABLED, status.blockedReason)
    }

    @Test
    fun `maintenance archives legacy chat facts and completes legacy job`() = runTest {
        val dbPath = Files.createTempDirectory("souz-memory-maintenance-test-").resolve("memory.db")
        seedLegacyV1MemoryDb(dbPath)
        val repository = SqliteMemoryRepository(dbPath)
        repository.listFacts(MemoryFactFilter())
        val controller = DesktopMemoryMaintenanceController(dbPath, InMemoryMemoryMaintenanceSettingsStore())
        controller.savePreferences(MemoryMaintenancePreferences(mode = MemoryMaintenanceMode.LOCAL_ONLY))

        val beforeRun = controller.status()
        assertEquals(1, beforeRun.pendingClusters)
        assertNull(beforeRun.blockedReason)

        val status = controller.runNow()

        assertEquals(0, status.pendingClusters)
        assertEquals(0, status.blockedClusters)
        assertEquals(MemoryMaintenanceBlockReason.NO_PENDING_CLUSTERS, status.blockedReason)
        assertEquals(MemoryFactStatus.RETIRED, repository.getFact("fact-legacy-chat")?.status)
        assertEquals(0, countRows(dbPath, "memory_maintenance_jobs", "status = 'PENDING'"))
        assertEquals(1, countRows(dbPath, "memory_maintenance_jobs", "status = 'DONE'"))
        assertEquals(0, countRows(dbPath, "memory_maintenance_jobs", "status = 'BLOCKED'"))
        assertNotNull(status.lastAttemptedAt)
        assertNotNull(status.lastCompletedAt)
    }

    @Test
    fun `dreamer consolidates pending scope into provenance linked replacement`() = runTest {
        val fixture = createFixture(owner = MemoryOwnerId("desktop-owner"))
        val scope = projectScope()
        val first = fixture.createManual(
            scope = scope,
            title = "Memory storage target",
            body = "Memory is desktop-only for now.",
        )
        val second = fixture.createManual(
            scope = scope,
            title = "Memory backend boundary",
            body = "Backend must not get memory yet.",
        )
        val sourceEventIds = listOf(first, second)
            .map { fact -> fixture.repository.getFactDetails(fact.id) }
            .mapNotNull { details -> details?.evidence?.singleOrNull()?.sourceEvent?.id }
        val controller = DesktopMemoryMaintenanceController(
            dbPath = fixture.dbPath,
            settingsStore = InMemoryMemoryMaintenanceSettingsStore(),
            worker = MemoryMaintenanceWorker(
                dbPath = fixture.dbPath,
                consolidator = FixedMemoryConsolidator(sourceEventIds),
                embeddingModel = fixture.embedder.model,
            ),
        )
        controller.savePreferences(MemoryMaintenancePreferences(mode = MemoryMaintenanceMode.LOCAL_ONLY))

        val status = controller.runNow()

        val activeFacts = fixture.repository.listFacts(
            MemoryFactFilter(
                ownerId = fixture.owner,
                scope = scope,
                statuses = setOf(MemoryFactStatus.ACTIVE),
            )
        )
        val replacement = activeFacts.single()
        val replacementDetails = fixture.repository.getFactDetails(replacement.id)

        assertEquals(0, status.pendingClusters)
        assertEquals(MemoryMaintenanceBlockReason.NO_PENDING_CLUSTERS, status.blockedReason)
        assertEquals(MemoryFactStatus.RETIRED, fixture.repository.getFact(first.id)?.status)
        assertEquals(MemoryFactStatus.RETIRED, fixture.repository.getFact(second.id)?.status)
        assertEquals("Memory rollout boundary", replacement.title)
        assertEquals("dreamer", replacement.createdBy)
        assertEquals(sourceEventIds.toSet(), replacementDetails?.evidence?.map { it.sourceEvent.id }?.toSet())
        assertTrue(replacementDetails?.evidence?.all { !it.evidence.evidenceText.isNullOrBlank() } == true)
        assertEquals(
            setOf(first.id, second.id),
            dreamerSupersededFactIds(fixture.dbPath, replacement.id),
        )
        assertEquals(1, countRows(fixture.dbPath, "memory_index_jobs", "fact_id = '${replacement.id}'"))
        assertEquals(1, countRows(fixture.dbPath, "memory_maintenance_jobs", "status = 'DONE'"))
    }

    @Test
    fun `dreamer rejects replacement backed by only one source fact`() = runTest {
        val fixture = createFixture(owner = MemoryOwnerId("desktop-owner"))
        val first = fixture.createManual(title = "First", body = "First durable project fact.", scope = projectScope())
        val second = fixture.createManual(title = "Second", body = "Second durable project fact.", scope = projectScope())
        val firstSource = fixture.repository.getFactDetails(first.id)?.evidence?.single()?.sourceEvent?.id
            ?: error("missing source")
        val controller = DesktopMemoryMaintenanceController(
            dbPath = fixture.dbPath,
            settingsStore = InMemoryMemoryMaintenanceSettingsStore(),
            worker = MemoryMaintenanceWorker(
                dbPath = fixture.dbPath,
                consolidator = FixedCandidatesMemoryConsolidator(
                    MemoryConsolidationCandidate(
                        kind = MemoryFactKind.PROJECT_DECISION,
                        title = "Partial replacement",
                        body = "First project fact.",
                        canonicalKey = null,
                        confidence = 0.9f,
                        sourceFactIds = listOf(first.id),
                        evidenceSourceEventIds = listOf(firstSource),
                    )
                ),
            ),
        )
        controller.savePreferences(MemoryMaintenancePreferences(mode = MemoryMaintenanceMode.LOCAL_ONLY))

        controller.runNow()

        assertEquals(MemoryFactStatus.ACTIVE, fixture.repository.getFact(first.id)?.status)
        assertEquals(MemoryFactStatus.ACTIVE, fixture.repository.getFact(second.id)?.status)
        assertEquals(0, countRows(fixture.dbPath, "memory_facts", "created_by = 'dreamer'"))
    }

    @Test
    fun `dreamer replaces duplicate subset and preserves unrelated region facts`() = runTest {
        val fixture = createFixture(owner = MemoryOwnerId("desktop-owner"))
        val scope = projectScope()
        val first = fixture.createManual(title = "Same target", body = "The project targets desktop users.", scope = scope)
        val second = fixture.createManual(title = "Desktop target", body = "Desktop users are the project target.", scope = scope)
        val unrelated = fixture.createManual(title = "Storage", body = "The project stores data in SQLite.", scope = scope)
        val sources = listOf(first, second).map { fact ->
            fixture.repository.getFactDetails(fact.id)?.evidence?.single()?.sourceEvent?.id
                ?: error("missing source")
        }
        val controller = DesktopMemoryMaintenanceController(
            dbPath = fixture.dbPath,
            settingsStore = InMemoryMemoryMaintenanceSettingsStore(),
            worker = MemoryMaintenanceWorker(
                dbPath = fixture.dbPath,
                consolidator = FixedCandidatesMemoryConsolidator(
                    MemoryConsolidationCandidate(
                        kind = MemoryFactKind.PROJECT_DECISION,
                        title = "Desktop target",
                        body = "The project targets desktop users.",
                        canonicalKey = null,
                        confidence = 0.9f,
                        sourceFactIds = listOf(first.id, second.id),
                        evidenceSourceEventIds = sources,
                    )
                ),
            ),
        )
        controller.savePreferences(MemoryMaintenancePreferences(mode = MemoryMaintenanceMode.LOCAL_ONLY))

        controller.runNow()

        assertEquals(MemoryFactStatus.RETIRED, fixture.repository.getFact(first.id)?.status)
        assertEquals(MemoryFactStatus.RETIRED, fixture.repository.getFact(second.id)?.status)
        assertEquals(MemoryFactStatus.ACTIVE, fixture.repository.getFact(unrelated.id)?.status)
        assertEquals(1, countRows(fixture.dbPath, "memory_facts", "created_by = 'dreamer' and status = 'ACTIVE'"))
    }

    @Test
    fun `dreamer defers failed job and succeeds on a later retry`() = runTest {
        val fixture = createFixture(owner = MemoryOwnerId("desktop-owner"))
        val first = fixture.createManual(title = "First", body = "First durable project fact.", scope = projectScope())
        val second = fixture.createManual(title = "Second", body = "Second durable project fact.", scope = projectScope())
        val consolidator = FailingOnceMemoryConsolidator()
        val settings = InMemoryMemoryMaintenanceSettingsStore().apply {
            put("MEMORY_MAINTENANCE_LAST_ERROR_CODE", "IllegalStateException")
        }
        val controller = DesktopMemoryMaintenanceController(
            dbPath = fixture.dbPath,
            settingsStore = settings,
            worker = MemoryMaintenanceWorker(fixture.dbPath, consolidator),
        )
        controller.savePreferences(MemoryMaintenancePreferences(mode = MemoryMaintenanceMode.LOCAL_ONLY))

        val deferred = controller.runNow()

        assertNull(deferred.lastErrorCode)
        assertEquals(1, countRows(fixture.dbPath, "memory_maintenance_jobs", "status = 'PENDING' and attempt_count = 1"))
        assertEquals(MemoryFactStatus.ACTIVE, fixture.repository.getFact(first.id)?.status)
        assertEquals(MemoryFactStatus.ACTIVE, fixture.repository.getFact(second.id)?.status)

        val completed = controller.runNow()

        assertNull(completed.lastErrorCode)
        assertEquals(2, consolidator.callCount)
        assertEquals(1, countRows(fixture.dbPath, "memory_facts", "created_by = 'dreamer' and status = 'ACTIVE'"))
        assertEquals(1, countRows(fixture.dbPath, "memory_maintenance_jobs", "status = 'DONE'"))
    }

    @Test
    fun `background dreamer respects retry backoff`() = runTest {
        val fixture = createFixture(owner = MemoryOwnerId("desktop-owner"))
        fixture.createManual(title = "First", body = "First durable project fact.", scope = projectScope())
        fixture.createManual(title = "Second", body = "Second durable project fact.", scope = projectScope())
        val consolidator = FailingOnceMemoryConsolidator()
        val controller = DesktopMemoryMaintenanceController(
            dbPath = fixture.dbPath,
            settingsStore = InMemoryMemoryMaintenanceSettingsStore(),
            worker = MemoryMaintenanceWorker(fixture.dbPath, consolidator),
        )
        controller.savePreferences(MemoryMaintenancePreferences(mode = MemoryMaintenanceMode.LOCAL_ONLY))
        controller.runNow()

        DesktopMemoryMaintenanceBackgroundRunner(controller, this).tick()

        assertEquals(1, consolidator.callCount)
        assertEquals(1, countRows(fixture.dbPath, "memory_maintenance_jobs", "status = 'PENDING' and attempt_count = 1"))
    }

    @Test
    fun `dreamer quality gate rejects non compact replacements`() = runTest {
        val fixture = createFixture(owner = MemoryOwnerId("desktop-owner"))
        val scope = projectScope()
        val first = fixture.createManual(
            scope = scope,
            title = "Memory storage target",
            body = "Memory is desktop-only for now.",
        )
        val second = fixture.createManual(
            scope = scope,
            title = "Memory backend boundary",
            body = "Backend must not get memory yet.",
        )
        val sourceEventIds = listOf(first, second)
            .map { fact -> fixture.repository.getFactDetails(fact.id) }
            .mapNotNull { details -> details?.evidence?.singleOrNull()?.sourceEvent?.id }
        val controller = DesktopMemoryMaintenanceController(
            dbPath = fixture.dbPath,
            settingsStore = InMemoryMemoryMaintenanceSettingsStore(),
            worker = MemoryMaintenanceWorker(
                dbPath = fixture.dbPath,
                consolidator = FixedCandidatesMemoryConsolidator(
                    MemoryConsolidationCandidate(
                        kind = MemoryFactKind.PROJECT_DECISION,
                        title = "Memory storage target",
                        body = "Memory is desktop-only for now.",
                        canonicalKey = "project.decision.memory.storage.target",
                        confidence = 0.9f,
                        evidenceSourceEventIds = sourceEventIds.take(1),
                    ),
                    MemoryConsolidationCandidate(
                        kind = MemoryFactKind.PROJECT_DECISION,
                        title = "Memory backend boundary",
                        body = "Backend must not get memory yet.",
                        canonicalKey = "project.decision.memory.backend.boundary",
                        confidence = 0.9f,
                        evidenceSourceEventIds = sourceEventIds.drop(1),
                    ),
                ),
                embeddingModel = fixture.embedder.model,
            ),
        )
        controller.savePreferences(MemoryMaintenancePreferences(mode = MemoryMaintenanceMode.LOCAL_ONLY))

        val status = controller.runNow()

        assertEquals(0, status.pendingClusters)
        assertEquals(MemoryMaintenanceBlockReason.NO_PENDING_CLUSTERS, status.blockedReason)
        assertEquals(MemoryFactStatus.ACTIVE, fixture.repository.getFact(first.id)?.status)
        assertEquals(MemoryFactStatus.ACTIVE, fixture.repository.getFact(second.id)?.status)
        assertEquals(0, countRows(fixture.dbPath, "memory_facts", "created_by = 'dreamer'"))
        assertEquals(1, countRows(fixture.dbPath, "memory_maintenance_jobs", "status = 'DONE'"))
    }

    @Test
    fun `retrieving durable facts queues dreamer neighborhood but session facts do not`() = runTest {
        val fixture = createFixture(owner = MemoryOwnerId("desktop-owner"))
        val projectFact = fixture.createManual(
            scope = projectScope(),
            title = "Dreamer retrieval candidate",
            body = "Dreamer should revisit retrieved durable project facts.",
        )
        fixture.createManual(
            scope = projectScope(),
            title = "Related project fact",
            body = "Dreamer needs at least two durable facts to compare.",
        )
        val sessionFact = fixture.createManual(
            scope = MemoryScope.session(MemorySessionId("chat-1")),
            title = "Session note",
            body = "Session-only notes are not durable Dreamer input.",
        )
        clearMaintenanceJobs(fixture.dbPath)

        fixture.repository.recordRetrieval(listOf(projectFact.id, sessionFact.id))

        assertEquals(
            1,
            countRows(
                fixture.dbPath,
                "memory_maintenance_jobs",
                "status = 'PENDING' and reasons like '%dreamer_region_rewrite%'",
            )
        )
        assertEquals(
            0,
            countRows(
                fixture.dbPath,
                "memory_maintenance_jobs",
                "cluster_key like '%session%'",
            )
        )
    }

    @Test
    fun `dreamer loads bounded semantic neighborhood around retrieved anchor`() = runTest {
        val fixture = createFixture(owner = MemoryOwnerId("desktop-owner"))
        val scope = projectScope()
        val anchor = fixture.createManual("Career target", "Work at an AI lab.", scope)
        val duplicate = fixture.createManual("AI lab career", "The career target is an AI lab.", scope)
        val unrelated = (1..9).map { index ->
            fixture.createManual("Unrelated $index", "Independent fact $index.", scope)
        }
        fixture.repository.replaceEmbedding(
            anchor.id,
            fixture.embedder.model,
            floatArrayOf(1f, 0f),
            anchor.contentHash,
        )
        fixture.repository.replaceEmbedding(
            duplicate.id,
            fixture.embedder.model,
            floatArrayOf(0.99f, 0.01f),
            duplicate.contentHash,
        )
        unrelated.forEachIndexed { index, fact ->
            fixture.repository.replaceEmbedding(
                fact.id,
                fixture.embedder.model,
                floatArrayOf(0f, 1f + index),
                fact.contentHash,
            )
        }
        clearMaintenanceJobs(fixture.dbPath)
        fixture.repository.recordRetrieval(listOf(anchor.id))
        val consolidator = RecordingMemoryConsolidator()
        val controller = DesktopMemoryMaintenanceController(
            dbPath = fixture.dbPath,
            settingsStore = InMemoryMemoryMaintenanceSettingsStore(),
            worker = MemoryMaintenanceWorker(
                fixture.dbPath,
                consolidator,
                embeddingModel = fixture.embedder.model,
            ),
        )
        controller.savePreferences(
            MemoryMaintenancePreferences(
                mode = MemoryMaintenanceMode.LOCAL_ONLY,
                modelAlias = LLMModel.LocalGemma4_E2B_It.alias,
            )
        )

        controller.runNow()

        val input = consolidator.inputs.single()
        assertEquals(8, input.facts.size)
        assertEquals(anchor.id, input.facts.first().fact.id)
        assertTrue(input.facts.any { it.fact.id == duplicate.id })
        assertEquals(LLMModel.LocalGemma4_E2B_It.alias, input.modelAlias)
    }

    @Test
    fun `failed optimistic update does not retire canonical conflict`() = runTest {
        val fixture = createFixture()
        val stale = fixture.createManual(
            title = "Old language preference",
            body = "User used to prefer Java.",
            canonicalKey = "user.preference.previous.language",
        )
        val active = fixture.createManual(
            title = "Active language preference",
            body = "User prefers Kotlin.",
            canonicalKey = "user.preference.language",
        )
        val updatedElsewhere = fixture.memoryService.updateFact(
            factId = stale.id,
            patch = MemoryFactPatch(title = "Updated elsewhere"),
        )
        val staleAttempt = stale.copy(
            title = "Stale update",
            canonicalKey = "user.preference.language",
            updatedAt = java.time.Instant.now(),
        )

        assertFailsWith<IllegalStateException> {
            fixture.repository.updateFact(staleAttempt, expectedUpdatedAt = stale.updatedAt)
        }

        assertEquals(MemoryFactStatus.ACTIVE, fixture.repository.getFact(active.id)?.status)
        assertEquals(MemoryFactStatus.ACTIVE, fixture.repository.getFact(updatedElsewhere.id)?.status)
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

    @Test
    fun `mismatched embedding dimension is ignored`() = runTest {
        val fixture = createFixture()
        val fact = fixture.createManual(
            title = "SQLite memory",
            body = "SQLite desktop memory Kotlin.",
        )
        val queryEmbedding = fixture.embedder.embedQuery("sqlite kotlin desktop")

        fixture.repository.replaceEmbedding(
            factId = fact.id,
            model = fixture.embedder.model,
            embedding = floatArrayOf(1f, 0f),
        )

        val hits = fixture.repository.searchFacts(
            scopes = listOf(globalScope()),
            model = fixture.embedder.model,
            queryEmbedding = queryEmbedding,
            limit = 5,
        )
        assertTrue(hits.none { it.fact.id == fact.id })
    }

    @Test
    fun `stale embedding content hash is ignored`() = runTest {
        val fixture = createFixture()
        val fact = fixture.createManual(
            title = "SQLite memory",
            body = "SQLite desktop memory Kotlin.",
        )
        fixture.embedder.mode = FakeEmbeddingClient.Mode.THROW_ON_DOCUMENT

        fixture.memoryService.updateFact(
            factId = fact.id,
            patch = MemoryFactPatch(body = "Rust desktop memory."),
        )

        val queryEmbedding = fixture.embedder.embedQuery("sqlite kotlin desktop")
        val hits = fixture.repository.searchFacts(
            scopes = listOf(globalScope()),
            model = fixture.embedder.model,
            queryEmbedding = queryEmbedding,
            limit = 5,
        )
        assertTrue(hits.none { it.fact.id == fact.id })
    }

    @Test
    fun `repository updateFact rejects stale updatedAt`() = runTest {
        val fixture = createFixture()
        val fact = fixture.createManual(
            scope = projectScope(),
            kind = MemoryFactKind.PROJECT_DECISION,
            title = "Initial storage",
            body = "Use Postgres for memory storage.",
        )
        val snapshot = fixture.repository.getFact(fact.id)
        assertNotNull(snapshot)

        val firstUpdate = snapshot.copy(
            title = "First storage",
            updatedAt = snapshot.updatedAt.plusMillis(1),
        )
        fixture.repository.updateFact(
            fact = firstUpdate,
            expectedUpdatedAt = snapshot.updatedAt,
            embedding = fixture.embedder.embedDocument(firstUpdate.embeddingText()),
            embeddingModel = fixture.embedder.model,
        )

        val staleUpdate = snapshot.copy(
            title = "Stale storage",
            updatedAt = snapshot.updatedAt.plusMillis(2),
        )
        val result = kotlin.runCatching {
            fixture.repository.updateFact(
                fact = staleUpdate,
                expectedUpdatedAt = snapshot.updatedAt,
                embedding = fixture.embedder.embedDocument(staleUpdate.embeddingText()),
                embeddingModel = fixture.embedder.model,
            )
        }

        assertTrue(result.isFailure)
        assertEquals("First storage", fixture.repository.getFact(fact.id)?.title)
    }

    @Test
    fun `capture keeps fact after embedding failure`() = runTest {
        val fixture = createFixture(
            writer = FixedWriter(
                candidate(
                    kind = MemoryFactKind.PROJECT_RULE,
                    title = "Write tests first",
                    body = "Implement features test-first in this project.",
                    scope = globalScope(),
                    confidence = 0.91f,
                    evidenceText = "Before implementing the feature, write tests first.",
                )
            )
        )
        fixture.embedder.mode = FakeEmbeddingClient.Mode.THROW_ON_DOCUMENT

        val created = fixture.capture(userMessage = "Before implementing the feature, write tests first.")

        assertEquals(1, created.size)
        assertEquals(1, fixture.countRows("memory_source_events"))
        assertEquals(1, fixture.countRows("memory_facts"))
        assertEquals(1, fixture.countRows("memory_fact_evidence"))
        assertEquals(1, fixture.countRows("memory_index_jobs"))
    }

    @Test
    fun `out-of-scope writer candidate is rejected`() = runTest {
        val fixture = createFixture(
            writer = FixedWriter(
                candidate(
                    kind = MemoryFactKind.SEMANTIC,
                    title = "Secret rule",
                    body = "This is a secret rule.",
                    scope = chatScope("other-chat"),
                    confidence = 0.9f,
                    evidenceText = "We use other-chat here."
                )
            )
        )

        val created = fixture.capture(
            primaryScope = chatScope("my-chat"),
            scopes = listOf(chatScope("my-chat"))
        )

        assertTrue(created.isEmpty())
    }

    @Test
    fun `invented scope is rejected`() = runTest {
        val fixture = createFixture(
            writer = FixedWriter(
                candidate(
                    kind = MemoryFactKind.SEMANTIC,
                    title = "Invented rule",
                    body = "This is an invented rule.",
                    scope = chatScope("invented-chat"),
                    confidence = 0.9f,
                    evidenceText = "We use invented-chat here."
                )
            )
        )

        val created = fixture.capture(
            primaryScope = chatScope("my-chat"),
            scopes = listOf(chatScope("my-chat"))
        )

        assertTrue(created.isEmpty())
    }

    private fun createFixture(
        writer: MemoryWriter = FixedWriter(),
        owner: MemoryOwnerId = MemoryOwnerId(LEGACY_OWNER_ID),
    ): Fixture = Files.createTempDirectory("souz-memory-test-").resolve("memory.db")
        .let { dbPath ->
            SqliteMemoryRepository(dbPath).let { repository ->
            FakeEmbeddingClient().let { embedder ->
                MemoryService(repository, embedder).let { service ->
                    Fixture(dbPath, owner, repository, embedder, service, MemoryCaptureService(service, writer))
                }
            }
        }
    }

    private data class Fixture(
        val dbPath: Path,
        val owner: MemoryOwnerId,
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
        canonicalKey: String? = null,
    ): MemoryFact = memoryService.createManualFact(
        CreateMemoryFactInput(
            ownerId = owner,
            scope = scope,
            kind = kind,
            title = title,
            body = body,
            pinned = pinned,
            canonicalKey = canonicalKey,
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
                ownerId = owner,
                scope = scope,
                sourceType = "manual",
                sourceRef = null,
                text = body,
            )
        )
        val factId = repository.insertFact(
            NewMemoryFact(
                ownerId = owner,
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

    private fun Fixture.countRows(table: String): Int = countRows(dbPath, table)

    private fun Fixture.countRows(table: String, where: String): Int = countRows(dbPath, table, where)

    private fun countRows(dbPath: Path, table: String, where: String? = null): Int {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { connection ->
            val sql = buildString {
                append("select count(*) from $table")
                if (where != null) append(" where $where")
            }
            connection.prepareStatement(sql).use { statement ->
                statement.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1)
                }
            }
        }
    }

    private fun dreamerSupersededFactIds(dbPath: Path, replacementFactId: String): Set<String> {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { connection ->
            connection.prepareStatement(
                """
                select superseded_fact_id
                from memory_fact_supersedes
                where replacement_fact_id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, replacementFactId)
                statement.executeQuery().use { rs ->
                    return buildSet {
                        while (rs.next()) add(rs.getString("superseded_fact_id"))
                    }
                }
            }
        }
    }

    private fun clearMaintenanceJobs(dbPath: Path) {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("delete from memory_maintenance_jobs")
            }
        }
    }

    private fun executeSql(dbPath: Path, sql: String) {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement -> statement.executeUpdate(sql) }
        }
    }

    private fun seedLegacyV1MemoryDb(dbPath: Path) {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { connection ->
            listOf(
                """
                create table memory_source_events (
                    id text primary key,
                    scope_type text not null,
                    scope_id text not null,
                    source_type text not null,
                    source_ref text,
                    text text not null,
                    metadata_json text not null default '{}',
                    created_at text not null
                )
                """.trimIndent(),
                """
                create table memory_facts (
                    id text primary key,
                    scope_type text not null,
                    scope_id text not null,
                    kind text not null,
                    title text not null,
                    body text not null,
                    slot_key text,
                    status text not null,
                    confidence real not null,
                    pinned integer not null,
                    created_by text not null,
                    created_at text not null,
                    updated_at text not null,
                    supersedes_fact_id text
                )
                """.trimIndent(),
                """
                create table memory_fact_evidence (
                    fact_id text not null,
                    source_event_id text not null,
                    evidence_text text,
                    primary key (fact_id, source_event_id)
                )
                """.trimIndent(),
                """
                create table memory_fact_embeddings (
                    fact_id text primary key,
                    embedding_model text not null,
                    embedding_blob blob not null,
                    dimension integer not null,
                    updated_at text not null
                )
                """.trimIndent(),
                """
                insert into memory_source_events(
                    id, scope_type, scope_id, source_type, source_ref, text, metadata_json, created_at
                ) values (
                    'source-legacy-chat',
                    'chat',
                    'chat-legacy',
                    'turn',
                    'chat-legacy',
                    'Legacy Kotlin chat memory.',
                    '{}',
                    '2026-05-24T10:15:30Z'
                )
                """.trimIndent(),
                """
                insert into memory_facts(
                    id, scope_type, scope_id, kind, title, body, slot_key, status, confidence, pinned,
                    created_by, created_at, updated_at, supersedes_fact_id
                ) values (
                    'fact-legacy-chat',
                    'chat',
                    'chat-legacy',
                    'SEMANTIC',
                    'Legacy Kotlin chat',
                    'Legacy Kotlin chat memory.',
                    null,
                    'ACTIVE',
                    1.0,
                    0,
                    'writer',
                    '2026-05-24T10:15:30Z',
                    '2026-05-24T11:15:30Z',
                    null
                )
                """.trimIndent(),
                """
                insert into memory_fact_evidence(fact_id, source_event_id, evidence_text)
                values ('fact-legacy-chat', 'source-legacy-chat', 'Legacy Kotlin chat memory.')
                """.trimIndent(),
            ).forEach { sql ->
                connection.createStatement().use { statement -> statement.execute(sql) }
            }
        }
    }

    private fun seedLegacySlotKeyMemoryDb(
        dbPath: Path,
        factId: String,
        scope: MemoryScope,
        slotKey: String,
    ) {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { connection ->
            listOf(
                """
                create table memory_source_events (
                    id text primary key,
                    scope_type text not null,
                    scope_id text not null,
                    source_type text not null,
                    source_ref text,
                    text text not null,
                    metadata_json text not null default '{}',
                    created_at text not null
                )
                """.trimIndent(),
                """
                create table memory_facts (
                    id text primary key,
                    scope_type text not null,
                    scope_id text not null,
                    kind text not null,
                    title text not null,
                    body text not null,
                    slot_key text,
                    status text not null,
                    confidence real not null,
                    pinned integer not null,
                    created_by text not null,
                    created_at text not null,
                    updated_at text not null,
                    supersedes_fact_id text
                )
                """.trimIndent(),
                """
                create table memory_fact_evidence (
                    fact_id text not null,
                    source_event_id text not null,
                    evidence_text text,
                    primary key (fact_id, source_event_id)
                )
                """.trimIndent(),
                """
                create table memory_fact_embeddings (
                    fact_id text primary key,
                    embedding_model text not null,
                    embedding_blob blob not null,
                    dimension integer not null,
                    updated_at text not null
                )
                """.trimIndent(),
            ).forEach { sql ->
                connection.createStatement().use { statement -> statement.execute(sql) }
            }
            connection.prepareStatement(
                """
                insert into memory_source_events(
                    id, scope_type, scope_id, source_type, source_ref, text, metadata_json, created_at
                ) values (?, ?, ?, 'turn', 'legacy', 'Legacy memory.', '{}', '2026-05-24T10:15:30Z')
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, "source-$factId")
                statement.setString(2, scope.type)
                statement.setString(3, scope.id)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                insert into memory_facts(
                    id, scope_type, scope_id, kind, title, body, slot_key, status, confidence, pinned,
                    created_by, created_at, updated_at, supersedes_fact_id
                ) values (?, ?, ?, 'PROJECT_DECISION', 'Memory storage target', 'Use Postgres for memory storage.',
                    ?, 'ACTIVE', 1.0, 0, 'writer', '2026-05-24T10:15:30Z', '2026-05-24T11:15:30Z', null)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, factId)
                statement.setString(2, scope.type)
                statement.setString(3, scope.id)
                statement.setString(4, slotKey)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "insert into memory_fact_evidence(fact_id, source_event_id, evidence_text) values (?, ?, 'Legacy memory.')"
            ).use { statement ->
                statement.setString(1, factId)
                statement.setString(2, "source-$factId")
                statement.executeUpdate()
            }
        }
    }

    private class FakeEmbeddingClient : EmbeddingClient {
        enum class Mode {
            NORMAL,
            THROW_ON_DOCUMENT,
            EMPTY_DOCUMENT,
        }

        override val model: String = "fake-embedding-v1"
        var mode: Mode = Mode.NORMAL
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
            return when (mode) {
                Mode.NORMAL -> embed(text)
                Mode.THROW_ON_DOCUMENT -> error("Fake embedder error")
                Mode.EMPTY_DOCUMENT -> FloatArray(0)
            }
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

    private class FlakyWriter(
        private var failures: Int,
        private vararg val candidates: MemoryFactCandidate,
    ) : MemoryWriter {
        var callCount: Int = 0
            private set

        override suspend fun extractCandidates(input: MemoryCaptureInput): List<MemoryFactCandidate> {
            callCount++
            if (failures > 0) {
                failures--
                throw MemoryWriterException("writer unavailable")
            }
            return candidates.toList()
        }
    }

    private class BlockingWriter(
        private vararg val candidates: MemoryFactCandidate,
    ) : MemoryWriter {
        val firstCallEntered = CompletableDeferred<Unit>()
        val releaseFirstCall = CompletableDeferred<Unit>()

        override suspend fun extractCandidates(input: MemoryCaptureInput): List<MemoryFactCandidate> {
            firstCallEntered.complete(Unit)
            releaseFirstCall.await()
            return candidates.toList()
        }
    }

    private class FixedMemoryConsolidator(
        private val sourceEventIds: List<String>,
    ) : MemoryConsolidator {
        override suspend fun consolidate(input: MemoryConsolidationInput): List<MemoryConsolidationCandidate> =
            listOf(
                MemoryConsolidationCandidate(
                    kind = MemoryFactKind.PROJECT_DECISION,
                    title = "Memory rollout boundary",
                    body = "Memory stays desktop-only until Android and backend wiring are explicitly added.",
                    canonicalKey = "project.decision.memory.rollout.boundary",
                    confidence = 0.9f,
                    importance = 0.9f,
                    sourceFactIds = input.facts.map { it.fact.id },
                    evidenceSourceEventIds = sourceEventIds,
                )
            )
    }

    private class FailingOnceMemoryConsolidator : MemoryConsolidator {
        var callCount: Int = 0
            private set

        override suspend fun consolidate(input: MemoryConsolidationInput): List<MemoryConsolidationCandidate> {
            callCount++
            if (callCount == 1) throw MemoryConsolidationException("temporary failure")
            return listOf(
                MemoryConsolidationCandidate(
                    kind = MemoryFactKind.PROJECT_DECISION,
                    title = "Combined",
                    body = "Compact project facts.",
                    canonicalKey = null,
                    confidence = 0.9f,
                    sourceFactIds = input.facts.map { it.fact.id },
                    evidenceSourceEventIds = input.facts.flatMap { details ->
                        details.evidence.map { it.sourceEvent.id }
                    },
                )
            )
        }
    }

    private class FixedCandidatesMemoryConsolidator(
        private vararg val candidates: MemoryConsolidationCandidate,
    ) : MemoryConsolidator {
        override suspend fun consolidate(input: MemoryConsolidationInput): List<MemoryConsolidationCandidate> =
            candidates.toList()
    }

    private class RecordingMemoryConsolidator : MemoryConsolidator {
        val inputs = mutableListOf<MemoryConsolidationInput>()

        override suspend fun consolidate(input: MemoryConsolidationInput): List<MemoryConsolidationCandidate> {
            inputs += input
            return emptyList()
        }
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
                    slotKey = "project.decision.memory.storage.target",
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
        requestedScope: RequestedMemoryScope? = scope?.toRequestedMemoryScope(),
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
            requestedScope = requestedScope,
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

    private class InMemoryMemoryMaintenanceSettingsStore : MemoryMaintenanceSettingsStore {
        private val values = mutableMapOf<String, String>()

        override fun put(key: String, value: String) {
            values[key] = value
        }

        override fun get(key: String): String? = values[key]
    }

    private fun MemoryFact.embeddingText(): String = buildString {
        appendLine(title)
        appendLine(body)
        appendLine("kind=$kind")
        appendLine("scope=${scope.type}:${scope.id}")
    }
}
