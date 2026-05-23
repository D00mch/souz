package ru.souz.memory

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.ArrayDeque
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import ru.souz.agent.memory.MemoryDocType
import ru.souz.agent.memory.MemoryEntityRecord
import ru.souz.agent.memory.MemoryEvidenceRecord
import ru.souz.agent.memory.MemoryEvidenceType
import ru.souz.agent.memory.MemoryFactRecord
import ru.souz.agent.memory.MemoryFactStatus
import ru.souz.agent.memory.MemoryInjectionRequest
import ru.souz.agent.memory.MemoryObjectKind
import ru.souz.agent.memory.MemoryScope
import ru.souz.agent.memory.MemoryScopeType
import ru.souz.agent.memory.MemoryTriggerType
import ru.souz.llms.EmbeddingsModel
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.paths.DefaultSouzPaths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MemoryRuntimeServicesTest {
    @Test
    fun `plain json fact proposal creates fact`() = runTest {
        val fixture = createFixture()
        fixture.chatApi.enqueueProposal(
            factsEnvelope(
                simpleFact(
                    scope = "${fixture.userScope.type.name}:${fixture.userScope.id}",
                    evidenceRefs = listOf("bundle:user_message:0"),
                )
            )
        )

        val result = fixture.services.write(
            ru.souz.agent.memory.MemoryWriteInput(
                userMessage = "Remember that I want commit messages in imperative mood.",
                assistantMessage = "I will remember that.",
                scope = fixture.userScope,
                turnRef = "turn-structured-remember",
                triggerType = MemoryTriggerType.EXPLICIT_REMEMBER_REQUEST,
            )
        )
        val latestAttempt = fixture.store.recentWriteAttempts(
            scope = fixture.userScope,
            limit = 1,
        ).single()
        val extraction = parseStoredExtraction(latestAttempt.candidatesJson)

        assertEquals(1, result.acceptedFacts.size)
        assertEquals("imperative mood", result.acceptedFacts.single().objectValueText)
        assertTrue(fixture.chatApi.requests.last().functions.isEmpty())
        assertTrue(fixture.chatApi.requests.last().messages.first().content.contains("long-lived goals"))
        assertTrue(fixture.chatApi.requests.last().messages.first().content.contains("Never return null"))
        assertTrue(fixture.chatApi.requests.last().messages.first().content.contains("My main goal is to work at Anthropic"))
        assertEquals("CONTENT", extraction.rawOutputKind)
        assertNotNull(extraction.rawOutput)
    }

    @Test
    fun `fenced json fact proposal creates fact`() = runTest {
        val fixture = createFixture()
        fixture.chatApi.enqueueProposal(
            """
            ```json
            ${factsEnvelope(simpleFact(scope = "${fixture.userScope.type.name}:${fixture.userScope.id}", evidenceRefs = "bundle:user_message:0"))}
            ```
            """.trimIndent()
        )

        val result = fixture.services.write(
            ru.souz.agent.memory.MemoryWriteInput(
                userMessage = "Remember that I want commit messages in imperative mood.",
                assistantMessage = "I will remember that.",
                scope = fixture.userScope,
                turnRef = "turn-fenced-json-remember",
                triggerType = MemoryTriggerType.EXPLICIT_REMEMBER_REQUEST,
            )
        )

        assertEquals(1, result.acceptedFacts.size)
        assertEquals("imperative mood", result.acceptedFacts.single().objectValueText)
    }

    @Test
    fun `function call fallback accepts simplified candidate shape`() = runTest {
        val fixture = createFixture()
        fixture.chatApi.enqueueStructuredArguments(
            mapOf(
                "facts" to listOf(
                    simpleFact(
                        scope = "${fixture.userScope.type.name}:${fixture.userScope.id}",
                        evidenceRefs = "bundle:user_message:0",
                    )
                )
            )
        )

        val result = fixture.services.write(
            ru.souz.agent.memory.MemoryWriteInput(
                userMessage = "Remember that I want commit messages in imperative mood.",
                assistantMessage = "I will remember that.",
                scope = fixture.userScope,
                turnRef = "turn-structured-simple",
                triggerType = MemoryTriggerType.EXPLICIT_REMEMBER_REQUEST,
            )
        )

        assertEquals(1, result.acceptedFacts.size)
        assertEquals(fixture.userScope, result.acceptedFacts.single().scope)
        assertEquals("imperative mood", result.acceptedFacts.single().objectValueText)
    }

    @Test
    fun `function call fallback defaults missing scope to current scope`() = runTest {
        val fixture = createFixture()
        val threadScope = MemoryScope(MemoryScopeType.THREAD, "thread-1")
        fixture.chatApi.enqueueStructuredArguments(
            mapOf(
                "facts" to listOf(
                    simpleFact(
                        scope = null,
                        evidenceRefs = listOf("bundle:user_message:0"),
                    )
                )
            )
        )

        val result = fixture.services.write(
            ru.souz.agent.memory.MemoryWriteInput(
                userMessage = "Remember that I want commit messages in imperative mood.",
                assistantMessage = "I will remember that.",
                scope = threadScope,
                turnRef = "turn-structured-default-scope",
                triggerType = MemoryTriggerType.EXPLICIT_REMEMBER_REQUEST,
            )
        )

        assertEquals(1, result.acceptedFacts.size)
        assertEquals(threadScope, result.acceptedFacts.single().scope)
    }

    @Test
    fun `plain non json response is ignored without invalid output rejection`() = runTest {
        val fixture = createFixture()
        fixture.chatApi.enqueueProposal("No durable facts to store.")

        val result = fixture.services.write(
            ru.souz.agent.memory.MemoryWriteInput(
                userMessage = "Thanks, that was helpful.",
                assistantMessage = "You're welcome.",
                scope = fixture.userScope,
                turnRef = "turn-non-json",
                triggerType = MemoryTriggerType.TASK_STATE_CHANGE,
            )
        )

        val latestAttempt = fixture.store.recentWriteAttempts(
            scope = fixture.userScope,
            limit = 1,
        ).single()
        val extraction = parseStoredExtraction(latestAttempt.candidatesJson)

        assertTrue(result.acceptedFacts.isEmpty())
        assertEquals(0, latestAttempt.rejectedCount)
        assertTrue(latestAttempt.rejectionReasonsJson.isNullOrBlank())
        assertEquals("NON_JSON_CONTENT", extraction.emptyReason)
        assertEquals("No durable facts to store.", extraction.rawOutput)
    }

    @Test
    fun `transient proposal failure retries once and then stores fact`() = runTest {
        val fixture = createFixture()
        fixture.chatApi.enqueueChatError(
            status = -1,
            message = "Connection error: Not enough data available",
        )
        fixture.chatApi.enqueueProposal(
            factsEnvelope(
                simpleFact(
                    scope = "${fixture.userScope.type.name}:${fixture.userScope.id}",
                    evidenceRefs = listOf("bundle:user_message:0"),
                )
            )
        )

        val result = fixture.services.write(
            ru.souz.agent.memory.MemoryWriteInput(
                userMessage = "Remember that I want commit messages in imperative mood.",
                assistantMessage = "I will remember that.",
                scope = fixture.userScope,
                turnRef = "turn-transient-retry",
                triggerType = MemoryTriggerType.EXPLICIT_REMEMBER_REQUEST,
            )
        )

        assertEquals(1, result.acceptedFacts.size)
        assertEquals(2, fixture.chatApi.requests.size)
    }

    @Test
    fun `non transient proposal failure does not retry`() = runTest {
        val fixture = createFixture()
        fixture.chatApi.enqueueChatError(
            status = 400,
            message = "Bad request",
        )

        val result = fixture.services.write(
            ru.souz.agent.memory.MemoryWriteInput(
                userMessage = "Remember that I want commit messages in imperative mood.",
                assistantMessage = "I will remember that.",
                scope = fixture.userScope,
                turnRef = "turn-non-transient-error",
                triggerType = MemoryTriggerType.EXPLICIT_REMEMBER_REQUEST,
            )
        )
        val latestAttempt = fixture.store.recentWriteAttempts(
            scope = fixture.userScope,
            limit = 1,
        ).single()

        assertTrue(result.acceptedFacts.isEmpty())
        assertEquals(1, fixture.chatApi.requests.size)
        assertTrue(latestAttempt.rejectionReasonsJson.orEmpty().contains("LLM_PROPOSAL_FAILED"))
    }

    @Test
    fun `explicit remember request creates fact via open ended proposal path`() = runTest {
        val fixture = createFixture()
        fixture.chatApi.enqueueProposal(
            proposalEnvelope(
                candidate(
                    predicate = "prefers_commit_style",
                    objectValueText = "imperative mood",
                    scope = fixture.userScope,
                    slotKey = "user.preferences.commit_style",
                    reasonToStore = "Stable explicit user preference for future coding work",
                    evidenceRefs = listOf("bundle:user_message:0"),
                    conflictPolicy = "SINGLE_ACTIVE_PER_SLOT",
                )
            )
        )

        val result = fixture.services.write(
            ru.souz.agent.memory.MemoryWriteInput(
                userMessage = "Remember that I want commit messages in imperative mood.",
                assistantMessage = "I will remember that.",
                scope = fixture.userScope,
                turnRef = "turn-remember",
                triggerType = MemoryTriggerType.EXPLICIT_REMEMBER_REQUEST,
            )
        )

        assertEquals(1, result.acceptedFacts.size)
        assertEquals("prefers_commit_style", result.acceptedFacts.single().predicate)
        assertEquals("imperative mood", result.acceptedFacts.single().objectValueText)
        assertEquals(
            listOf("imperative mood"),
            fixture.store.listActiveFacts(fixture.userScope).mapNotNull { it.objectValueText }
        )
    }

    @Test
    fun `casual chit chat creates no fact`() = runTest {
        val fixture = createFixture()
        fixture.chatApi.enqueueProposal(proposalEnvelope())

        val result = fixture.services.write(
            ru.souz.agent.memory.MemoryWriteInput(
                userMessage = "Thanks, that was helpful.",
                assistantMessage = "You're welcome.",
                scope = fixture.userScope,
                turnRef = "turn-chat",
                triggerType = MemoryTriggerType.TASK_STATE_CHANGE,
            )
        )

        assertTrue(result.acceptedFacts.isEmpty())
        assertTrue(fixture.store.listActiveFacts(fixture.userScope).isEmpty())
    }

    @Test
    fun `assistant only statement is rejected`() = runTest {
        val fixture = createFixture()
        fixture.chatApi.enqueueProposal(
            proposalEnvelope(
                candidate(
                    predicate = "prefers_language",
                    objectValueText = "ru",
                    scope = fixture.userScope,
                    slotKey = "user.profile.language",
                    reasonToStore = "Assistant guessed a language preference",
                    evidenceRefs = listOf("bundle:assistant_message:0"),
                    conflictPolicy = "SINGLE_ACTIVE_PER_SLOT",
                )
            )
        )

        val result = fixture.services.write(
            ru.souz.agent.memory.MemoryWriteInput(
                userMessage = null,
                assistantMessage = "You prefer Russian, so I will continue in Russian.",
                scope = fixture.userScope,
                turnRef = "turn-assistant-only",
                triggerType = MemoryTriggerType.TASK_STATE_CHANGE,
            )
        )

        assertTrue(result.acceptedFacts.isEmpty())
        assertTrue(
            fixture.store.recentWriteAttempts(scope = fixture.userScope, limit = 1)
                .single()
                .rejectionReasonsJson
                .orEmpty()
                .contains("ASSISTANT_ONLY_EVIDENCE")
        )
    }

    @Test
    fun `candidate without resolvable evidence is rejected`() = runTest {
        val fixture = createFixture()
        fixture.chatApi.enqueueProposal(
            proposalEnvelope(
                candidate(
                    predicate = "prefers_review_depth",
                    objectValueText = "concise",
                    scope = fixture.userScope,
                    slotKey = "user.preferences.review_depth",
                    reasonToStore = "Potentially useful preference",
                    evidenceRefs = listOf("bundle:missing:0"),
                    conflictPolicy = "SINGLE_ACTIVE_PER_SLOT",
                )
            )
        )

        val result = fixture.services.write(
            ru.souz.agent.memory.MemoryWriteInput(
                userMessage = "Remember that I like concise reviews.",
                assistantMessage = "Understood.",
                scope = fixture.userScope,
                turnRef = "turn-missing-evidence",
                triggerType = MemoryTriggerType.EXPLICIT_REMEMBER_REQUEST,
            )
        )

        assertTrue(result.acceptedFacts.isEmpty())
        assertTrue(
            fixture.store.recentWriteAttempts(scope = fixture.userScope, limit = 1)
                .single()
                .rejectionReasonsJson
                .orEmpty()
                .contains("NO_EVIDENCE_REFS")
        )
    }

    @Test
    fun `write indexes active fact and retrieval returns it`() = runTest {
        val fixture = createFixture()
        fixture.chatApi.enqueueProposal(
            proposalEnvelope(
                candidate(
                    predicate = "requires",
                    objectValueText = "write tests first",
                    scope = fixture.userScope,
                    slotKey = "user.constraints.tests_first",
                    reasonToStore = "Stable explicit user constraint",
                    evidenceRefs = listOf("bundle:user_message:0"),
                    conflictPolicy = "SINGLE_ACTIVE_PER_SLOT",
                )
            )
        )

        val result = fixture.services.write(
            ru.souz.agent.memory.MemoryWriteInput(
                userMessage = "Before implementing the feature, write tests first.",
                assistantMessage = "I will write tests first.",
                scope = fixture.userScope,
                turnRef = "turn-1",
                triggerType = MemoryTriggerType.USER_PROFILE_SIGNAL,
            )
        )
        val injection = fixture.services.inject(
            MemoryInjectionRequest(
                queryText = "What constraints should I follow before implementation?",
                scope = fixture.userScope,
                turnRef = "turn-2",
            )
        )

        assertEquals(1, result.acceptedFacts.size)
        assertTrue(injection.renderedBlock.contains("write tests"))
        assertTrue(
            fixture.store.listEmbeddingDocs(
                scopes = listOf(fixture.userScope),
                docTypes = setOf(MemoryDocType.PROFILE),
                fingerprint = fixture.fingerprintState.model.name,
            ).any { it.text.contains("write tests") }
        )
    }

    @Test
    fun `superseded fact is removed from projection`() = runTest {
        val fixture = createFixture()
        fixture.chatApi.enqueueProposal(
            proposalEnvelope(
                candidate(
                    predicate = "requires",
                    objectValueText = "write tests first",
                    scope = fixture.userScope,
                    slotKey = "user.constraints.tests_first",
                    reasonToStore = "Stable explicit user constraint",
                    evidenceRefs = listOf("bundle:user_message:0"),
                    conflictPolicy = "SINGLE_ACTIVE_PER_SLOT",
                )
            )
        )
        fixture.services.write(
            ru.souz.agent.memory.MemoryWriteInput(
                userMessage = "Write tests first.",
                assistantMessage = "Understood.",
                scope = fixture.userScope,
                turnRef = "turn-1",
                triggerType = MemoryTriggerType.USER_PROFILE_SIGNAL,
            )
        )
        fixture.chatApi.enqueueProposal(
            proposalEnvelope(
                candidate(
                    predicate = "requires",
                    objectValueText = "write unit tests first",
                    scope = fixture.userScope,
                    slotKey = "user.constraints.tests_first",
                    reasonToStore = "Updated explicit user constraint",
                    evidenceRefs = listOf("bundle:user_message:0"),
                    conflictPolicy = "SINGLE_ACTIVE_PER_SLOT",
                )
            )
        )
        fixture.services.write(
            ru.souz.agent.memory.MemoryWriteInput(
                userMessage = "Before implementation, write unit tests first.",
                assistantMessage = "Understood.",
                scope = fixture.userScope,
                turnRef = "turn-2",
                triggerType = MemoryTriggerType.CORRECTION_OF_PREVIOUS_FACT,
            )
        )
        val injection = fixture.services.inject(
            MemoryInjectionRequest(
                queryText = "What should I do before implementation?",
                scope = fixture.userScope,
                turnRef = "turn-3",
            )
        )

        assertTrue(injection.renderedBlock.contains("unit tests"))
        assertFalse(injection.renderedBlock.contains("write tests first"))
        assertEquals(
            listOf("write unit tests first"),
            fixture.store.listActiveFacts(fixture.userScope).mapNotNull { it.objectValueText?.lowercase() }
        )
    }

    @Test
    fun `diagnostics keep rejection reason for duplicate write`() = runTest {
        val fixture = createFixture()
        val duplicateCandidate = candidate(
            predicate = "requires",
            objectValueText = "write tests first",
            scope = fixture.userScope,
            slotKey = "user.constraints.tests_first",
            reasonToStore = "Stable explicit user constraint",
            evidenceRefs = listOf("bundle:user_message:0"),
            conflictPolicy = "SINGLE_ACTIVE_PER_SLOT",
        )
        fixture.chatApi.enqueueProposal(proposalEnvelope(duplicateCandidate))
        fixture.services.write(
            ru.souz.agent.memory.MemoryWriteInput(
                userMessage = "Before implementing the feature, write tests first.",
                assistantMessage = "I will write tests first.",
                scope = fixture.userScope,
                turnRef = "turn-dup-1",
                triggerType = MemoryTriggerType.USER_PROFILE_SIGNAL,
            )
        )
        fixture.chatApi.enqueueProposal(proposalEnvelope(duplicateCandidate))
        fixture.services.write(
            ru.souz.agent.memory.MemoryWriteInput(
                userMessage = "Before implementing the feature, write tests first.",
                assistantMessage = "I will write tests first.",
                scope = fixture.userScope,
                turnRef = "turn-dup-2",
                triggerType = MemoryTriggerType.USER_PROFILE_SIGNAL,
            )
        )

        val latestAttempt = fixture.store.recentWriteAttempts(
            scope = fixture.userScope,
            limit = 1,
        ).single()

        assertEquals(0, latestAttempt.acceptedCount)
        assertEquals(1, latestAttempt.rejectedCount)
        assertTrue(latestAttempt.rejectionReasonsJson.orEmpty().contains("DUPLICATE_ACTIVE_FACT"))
    }

    @Test
    fun `only active facts appear in prompt injection`() = runTest {
        val fixture = createFixture()
        val active = seedUserPreferenceFact(
            store = fixture.store,
            scope = fixture.userScope,
            predicate = "prefers_language",
            value = "ru",
            slotKey = "user.profile.language",
            createdAt = Instant.parse("2026-05-01T08:00:00Z"),
        )
        seedUserPreferenceFact(
            store = fixture.store,
            scope = fixture.userScope,
            predicate = "prefers_language",
            value = "en",
            slotKey = "user.profile.legacy_language",
            status = MemoryFactStatus.FORGOTTEN,
            createdAt = Instant.parse("2026-05-01T07:00:00Z"),
        )
        fixture.services.rebuildProjection()

        val injection = fixture.services.inject(
            MemoryInjectionRequest(
                queryText = "Which language should you use?",
                scope = fixture.userScope,
                turnRef = "turn-active-only",
            )
        )

        assertTrue(injection.renderedBlock.contains(active.objectValueText.orEmpty()))
        assertFalse(injection.renderedBlock.contains("User language: en"))
    }

    @Test
    fun `prompt injection keeps only one fact per slot key`() = runTest {
        val fixture = createFixture()
        val chatScope = MemoryScope(MemoryScopeType.CHAT, "chat-1")
        seedUserPreferenceFact(
            store = fixture.store,
            scope = fixture.userScope,
            predicate = "prefers_language",
            value = "ru",
            slotKey = "user.profile.language",
            createdAt = Instant.parse("2026-05-01T08:00:00Z"),
        )
        seedUserPreferenceFact(
            store = fixture.store,
            scope = chatScope,
            predicate = "prefers_language",
            value = "en",
            slotKey = "user.profile.language",
            createdAt = Instant.parse("2026-05-01T09:00:00Z"),
        )
        fixture.services.rebuildProjection()

        val injection = fixture.services.inject(
            MemoryInjectionRequest(
                queryText = "Which language should you use?",
                scope = chatScope,
                turnRef = "turn-slot-dedupe",
            )
        )

        assertTrue(injection.renderedBlock.contains("User language: en"))
        assertFalse(injection.renderedBlock.contains("User language: ru"))
    }

    @Test
    fun `fingerprint mismatch blocks stale vector search`() = runTest {
        val fixture = createFixture()
        val scope = MemoryScope(MemoryScopeType.PROJECT, "souz")
        seedProjectFact(
            store = fixture.store,
            scope = scope,
            value = "agent module",
        )
        fixture.services.rebuildProjection()
        fixture.fingerprintState.model = EmbeddingsModel.QwenEmbeddings

        val injection = fixture.services.inject(
            MemoryInjectionRequest(
                queryText = "Which module is related to this project?",
                scope = scope,
                turnRef = "turn-4",
            )
        )

        assertTrue(injection.renderedBlock.isBlank())
    }

    @Test
    fun `projection rebuild reconstructs docs from canonical storage`() = runTest {
        val fixture = createFixture()
        seedProjectFact(
            store = fixture.store,
            scope = MemoryScope(MemoryScopeType.PROJECT, "souz"),
            value = "backend module",
        )

        fixture.services.rebuildProjection()
        val injection = fixture.services.inject(
            MemoryInjectionRequest(
                queryText = "backend module",
                scope = MemoryScope(MemoryScopeType.PROJECT, "souz"),
                turnRef = "turn-5",
            )
        )

        assertTrue(injection.renderedBlock.contains("backend module"))
    }

    @Test
    fun `deleting projection directory does not damage canonical memory`() = runTest {
        val fixture = createFixture()
        seedProjectFact(
            store = fixture.store,
            scope = MemoryScope(MemoryScopeType.PROJECT, "souz"),
            value = "sharedLogic module",
        )
        fixture.services.rebuildProjection()

        fixture.indexDir.toFile().deleteRecursively()

        assertEquals(1, fixture.store.listFacts(scope = MemoryScope(MemoryScopeType.PROJECT, "souz")).size)
        fixture.services.rebuildProjection()
        val injection = fixture.services.inject(
            MemoryInjectionRequest(
                queryText = "sharedLogic module",
                scope = MemoryScope(MemoryScopeType.PROJECT, "souz"),
                turnRef = "turn-6",
            )
        )
        assertTrue(injection.renderedBlock.contains("sharedLogic module"))
    }

    private suspend fun seedUserPreferenceFact(
        store: SqliteMemoryStore,
        scope: MemoryScope,
        predicate: String,
        value: String,
        slotKey: String,
        status: MemoryFactStatus = MemoryFactStatus.ACTIVE,
        createdAt: Instant,
    ): MemoryFactRecord {
        val subject = store.resolveOrUpsertEntity(
            MemoryEntityRecord(
                scope = scope,
                entityType = "USER",
                canonicalName = "current_user",
                displayName = "current_user",
                normalizedKey = "user/current_user",
            )
        )
        val evidence = store.insertEvidence(
            MemoryEvidenceRecord(
                scope = scope,
                evidenceType = MemoryEvidenceType.USER_MESSAGE,
                sourceRef = "seed:$slotKey",
                contentExcerpt = value,
                createdAt = createdAt,
            )
        )
        return store.insertFact(
            MemoryFactRecord(
                scope = scope,
                subjectEntityId = subject.id!!,
                predicate = predicate,
                objectKind = MemoryObjectKind.TEXT,
                objectValueText = value,
                slotKey = slotKey,
                confidence = 0.95,
                status = status,
                reasonToStore = "seed",
                createdAt = createdAt,
                validFrom = createdAt,
            ),
            evidenceIds = listOf(evidence.id!!),
        )
    }

    private suspend fun seedProjectFact(
        store: SqliteMemoryStore,
        scope: MemoryScope,
        value: String,
    ) {
        val subject = store.resolveOrUpsertEntity(
            MemoryEntityRecord(
                scope = scope,
                entityType = "PROJECT",
                canonicalName = "Souz",
                displayName = "Souz",
                normalizedKey = "project/souz",
            )
        )
        val evidence = store.insertEvidence(
            MemoryEvidenceRecord(
                scope = scope,
                evidenceType = MemoryEvidenceType.SYSTEM_METADATA,
                sourceRef = "seed:$value",
                contentExcerpt = value,
            )
        )
        store.insertFact(
            MemoryFactRecord(
                scope = scope,
                subjectEntityId = subject.id!!,
                predicate = "uses_module",
                objectKind = MemoryObjectKind.TEXT,
                objectValueText = value,
                slotKey = "project.souz.module.${value.replace(' ', '_')}",
                confidence = 0.95,
                status = MemoryFactStatus.ACTIVE,
                reasonToStore = "seed",
            ),
            evidenceIds = listOf(evidence.id!!),
        )
    }

    private fun createFixture(): Fixture {
        val stateRoot = Files.createTempDirectory("memory-runtime-services")
        val store = SqliteMemoryStore(paths = DefaultSouzPaths(stateRoot = stateRoot))
        val fingerprintState = FingerprintState()
        val chatApi = TestMemoryChatApi()
        val services = MemoryRuntimeServices(
            store = store,
            embeddingsApi = chatApi,
            embeddingsFingerprint = { fingerprintState.model.name },
            userScope = MemoryScope(MemoryScopeType.USER, "local-user"),
            vectorIndexDir = stateRoot.resolve("memory-vector-index"),
        )
        return Fixture(
            store = store,
            services = services,
            fingerprintState = fingerprintState,
            userScope = MemoryScope(MemoryScopeType.USER, "local-user"),
            indexDir = stateRoot.resolve("memory-vector-index"),
            chatApi = chatApi,
        )
    }

    private data class Fixture(
        val store: SqliteMemoryStore,
        val services: MemoryRuntimeServices,
        val fingerprintState: FingerprintState,
        val userScope: MemoryScope,
        val indexDir: java.nio.file.Path,
        val chatApi: TestMemoryChatApi,
    )
}

private class FingerprintState(
    var model: EmbeddingsModel = EmbeddingsModel.GigaEmbeddings,
)

private class TestMemoryChatApi : ru.souz.llms.LLMChatAPI {
    val requests = mutableListOf<LLMRequest.Chat>()
    private val queuedResponses = ArrayDeque<LLMResponse.Chat>()

    fun enqueueProposal(json: String) {
        queuedResponses.addLast(
            LLMResponse.Chat.Ok(
                choices = listOf(
                    LLMResponse.Choice(
                        message = LLMResponse.Message(
                            content = json,
                            role = LLMMessageRole.assistant,
                            functionCall = null,
                            functionsStateId = null,
                        ),
                        index = 0,
                        finishReason = LLMResponse.FinishReason.stop,
                    )
                ),
                created = System.currentTimeMillis(),
                model = "test-model",
                usage = LLMResponse.Usage(10, 10, 20, 0),
            )
        )
    }

    fun enqueueStructuredProposal(vararg candidates: Map<String, Any?>) {
        enqueueStructuredArguments(mapOf("candidates" to candidates.toList()))
    }

    @Suppress("UNCHECKED_CAST")
    fun enqueueStructuredArguments(arguments: Map<String, Any?>) {
        queuedResponses.addLast(
            LLMResponse.Chat.Ok(
                choices = listOf(
                    LLMResponse.Choice(
                        message = LLMResponse.Message(
                            content = "",
                            role = LLMMessageRole.assistant,
                            functionCall = LLMResponse.FunctionCall(
                                name = "propose_memory_candidates",
                                arguments = arguments as Map<String, Any>,
                            ),
                            functionsStateId = "memory-call-1",
                        ),
                        index = 0,
                        finishReason = LLMResponse.FinishReason.stop,
                    )
                ),
                created = System.currentTimeMillis(),
                model = "test-model",
                usage = LLMResponse.Usage(10, 10, 20, 0),
            )
        )
    }

    fun enqueueChatError(
        status: Int,
        message: String,
    ) {
        queuedResponses.addLast(
            LLMResponse.Chat.Error(
                status = status,
                message = message,
            )
        )
    }

    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat {
        requests += body
        return if (queuedResponses.isEmpty()) {
            LLMResponse.Chat.Ok(
                choices = listOf(
                    LLMResponse.Choice(
                        message = LLMResponse.Message(
                            content = proposalEnvelope(),
                            role = LLMMessageRole.assistant,
                            functionCall = null,
                            functionsStateId = null,
                        ),
                        index = 0,
                        finishReason = LLMResponse.FinishReason.stop,
                    )
                ),
                created = System.currentTimeMillis(),
                model = body.model,
                usage = LLMResponse.Usage(10, 10, 20, 0),
            )
        } else {
            queuedResponses.removeFirst()
        }
    }

    override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> =
        error("Streaming is not used in this test.")

    override suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings =
        LLMResponse.Embeddings.Ok(
            data = body.input.mapIndexed { index, text ->
                LLMResponse.Embedding(
                    embedding = vectorFor(text),
                    index = index,
                    objectType = "embedding",
                )
            },
            model = "test-embeddings",
            objectType = "list",
        )

    override suspend fun uploadFile(file: File): LLMResponse.UploadFile =
        error("File upload is not used in this test.")

    override suspend fun downloadFile(fileId: String): String? =
        error("File download is not used in this test.")

    override suspend fun balance(): LLMResponse.Balance =
        error("Balance is not used in this test.")

    private fun vectorFor(text: String): List<Double> {
        val normalized = text.lowercase()
        return listOf(
            score(normalized, "test", "tests", "unit"),
            score(normalized, "module", "backend", "sharedlogic", "agent"),
            score(normalized, "language", "russian", "english", "imperative", "concise"),
            if (normalized.contains("constraint") || normalized.contains("before")) 1.0 else 0.0,
        )
    }

    private fun score(text: String, vararg terms: String): Double =
        terms.sumOf { term -> if (text.contains(term)) 1.0 else 0.0 }
}

private fun proposalEnvelope(vararg candidates: Map<String, Any?>): String =
    jacksonObjectMapper()
        .findAndRegisterModules()
        .writeValueAsString(mapOf("candidates" to candidates.toList()))

private fun factsEnvelope(vararg facts: Map<String, Any?>): String =
    jacksonObjectMapper()
        .findAndRegisterModules()
        .writeValueAsString(mapOf("facts" to facts.toList()))

private fun parseStoredExtraction(json: String): StoredExtractionAuditEnvelope =
    jacksonObjectMapper()
        .findAndRegisterModules()
        .readValue(json, StoredExtractionAuditEnvelope::class.java)

private fun candidate(
    predicate: String,
    objectValueText: String,
    scope: MemoryScope,
    slotKey: String? = null,
    reasonToStore: String,
    evidenceRefs: List<String>,
    conflictPolicy: String? = null,
): Map<String, Any?> =
    linkedMapOf<String, Any?>(
        "subjectEntityType" to "USER",
        "subjectCanonicalName" to "current_user",
        "subjectDisplayName" to "current_user",
        "subjectNormalizedKey" to "user/current_user",
        "predicate" to predicate,
        "objectKind" to MemoryObjectKind.TEXT.name,
        "objectValueText" to objectValueText,
        "scope" to mapOf("type" to scope.type.name, "id" to scope.id),
        "slotKey" to slotKey,
        "confidence" to 0.96,
        "reasonToStore" to reasonToStore,
        "evidenceRefs" to evidenceRefs,
        "suggestedStatus" to MemoryFactStatus.ACTIVE.name,
        "conflictPolicy" to conflictPolicy,
    )

private fun simpleFact(
    scope: String?,
    evidenceRefs: Any,
): Map<String, Any?> =
    linkedMapOf<String, Any?>(
        "predicate" to "prefers_commit_style",
        "value" to "imperative mood",
        "scope" to scope,
        "slotKey" to "user.preferences.commit_style",
        "confidence" to "0.96",
        "reasonToStore" to "Stable explicit user preference for future coding work",
        "evidenceRefs" to evidenceRefs,
        "conflictPolicy" to "single_active_per_slot",
    )

private data class StoredExtractionAuditEnvelope(
    val audits: List<Any?> = emptyList(),
    val rawOutput: String? = null,
    val rawOutputKind: String? = null,
    val emptyReason: String? = null,
)
