package ru.souz.memory

import java.io.File
import java.nio.file.Files
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
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.paths.DefaultSouzPaths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryRuntimeServicesTest {
    @Test
    fun `write indexes active fact and retrieval returns it`() = runTest {
        val fixture = createFixture()

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

        fixture.services.write(
            ru.souz.agent.memory.MemoryWriteInput(
                userMessage = "Write tests first.",
                assistantMessage = "Understood.",
                scope = fixture.userScope,
                turnRef = "turn-1",
                triggerType = MemoryTriggerType.USER_PROFILE_SIGNAL,
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
        assertFalse(injection.renderedBlock.contains("Write tests first."))
        assertEquals(
            listOf("write unit tests first"),
            fixture.store.listActiveFacts(fixture.userScope).mapNotNull { it.objectValueText?.lowercase() }
        )
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
        val services = MemoryRuntimeServices(
            store = store,
            embeddingsApi = KeywordEmbeddingsApi(),
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
        )
    }

    private data class Fixture(
        val store: SqliteMemoryStore,
        val services: MemoryRuntimeServices,
        val fingerprintState: FingerprintState,
        val userScope: MemoryScope,
        val indexDir: java.nio.file.Path,
    )
}

private class FingerprintState(
    var model: EmbeddingsModel = EmbeddingsModel.GigaEmbeddings,
)

private class KeywordEmbeddingsApi : ru.souz.llms.LLMChatAPI {
    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat =
        error("Chat is not used in this test.")

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
            score(normalized, "language", "russian", "english"),
            if (normalized.contains("constraint") || normalized.contains("before")) 1.0 else 0.0,
        )
    }

    private fun score(text: String, vararg terms: String): Double =
        terms.sumOf { term -> if (text.contains(term)) 1.0 else 0.0 }
}
