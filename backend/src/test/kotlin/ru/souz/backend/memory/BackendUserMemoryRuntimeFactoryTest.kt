package ru.souz.backend.memory

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.nio.file.Files
import java.util.ArrayDeque
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import ru.souz.agent.memory.MemoryFactStatus
import ru.souz.agent.memory.MemoryInjectionRequest
import ru.souz.agent.memory.MemoryObjectKind
import ru.souz.agent.memory.MemoryScope
import ru.souz.agent.memory.MemoryScopeType
import ru.souz.agent.memory.MemoryTriggerType
import ru.souz.agent.memory.MemoryWriteInput
import ru.souz.backend.TestSettingsProvider
import ru.souz.backend.storage.memory.InMemoryBackendMemoryStore
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackendUserMemoryRuntimeFactoryTest {
    @Test
    fun `backend runtime skips automatic memory when disabled and resumes on the next create`() = runTest {
        val settingsProvider = TestSettingsProvider().apply {
            memoryEnabled = true
        }
        val chatApi = TestBackendMemoryChatApi()
        val scope = MemoryScope(MemoryScopeType.CHAT, "chat-1")
        val factory = BackendUserMemoryRuntimeFactory(
            store = InMemoryBackendMemoryStore(),
            settingsProvider = settingsProvider,
            llmApiFactory = { _, _ -> chatApi },
            indexDirResolver = { userId ->
                Files.createTempDirectory("backend-memory-toggle-").resolve(userId)
            },
        )

        chatApi.enqueueProposal(
            proposalEnvelope(
                candidate(
                    predicate = "prefers_commit_style",
                    objectValueText = "imperative mood",
                    scope = scope,
                    slotKey = "user.preferences.commit_style",
                    reasonToStore = "Stable explicit user preference for future coding work",
                    evidenceRefs = listOf("bundle:user_message:0"),
                    conflictPolicy = "SINGLE_ACTIVE_PER_SLOT",
                )
            )
        )
        val enabledRuntime = factory.create(userId = "user-a", requestId = "req-1")
        val enabledWrite = enabledRuntime.write(
            MemoryWriteInput(
                userMessage = "Remember that I want commit messages in imperative mood.",
                assistantMessage = "Understood.",
                scope = scope,
                turnRef = "turn-1",
                triggerType = MemoryTriggerType.EXPLICIT_REMEMBER_REQUEST,
            )
        )
        assertEquals(1, enabledWrite.acceptedFacts.size)

        val enabledInjection = enabledRuntime.inject(
            MemoryInjectionRequest(
                queryText = "What commit style should you use?",
                scope = scope,
                turnRef = "turn-2",
            )
        )
        assertTrue(enabledInjection.renderedBlock.contains("imperative mood"))

        settingsProvider.memoryEnabled = false
        val disabledRuntime = factory.create(userId = "user-a", requestId = "req-2")
        val disabledWrite = disabledRuntime.write(
            MemoryWriteInput(
                userMessage = "Remember that I prefer verbose explanations.",
                assistantMessage = "Understood.",
                scope = scope,
                turnRef = "turn-3",
                triggerType = MemoryTriggerType.EXPLICIT_REMEMBER_REQUEST,
            )
        )
        val disabledInjection = disabledRuntime.inject(
            MemoryInjectionRequest(
                queryText = "What commit style should you use?",
                scope = scope,
                turnRef = "turn-4",
            )
        )
        assertEquals(0, disabledWrite.acceptedFacts.size)
        assertTrue(disabledInjection.renderedBlock.isBlank())

        settingsProvider.memoryEnabled = true
        val reenabledRuntime = factory.create(userId = "user-a", requestId = "req-3")
        val reenabledInjection = reenabledRuntime.inject(
            MemoryInjectionRequest(
                queryText = "What commit style should you use?",
                scope = scope,
                turnRef = "turn-5",
            )
        )
        assertTrue(reenabledInjection.renderedBlock.contains("imperative mood"))
    }

    @Test
    fun `backend manual maintenance still works while automatic memory is disabled`() = runTest {
        val settingsProvider = TestSettingsProvider().apply {
            memoryEnabled = true
        }
        val chatApi = TestBackendMemoryChatApi()
        val scope = MemoryScope(MemoryScopeType.CHAT, "chat-1")
        val factory = BackendUserMemoryRuntimeFactory(
            store = InMemoryBackendMemoryStore(),
            settingsProvider = settingsProvider,
            llmApiFactory = { _, _ -> chatApi },
            indexDirResolver = { userId ->
                Files.createTempDirectory("backend-memory-maintenance-").resolve(userId)
            },
        )

        chatApi.enqueueProposal(
            proposalEnvelope(
                candidate(
                    predicate = "prefers_commit_style",
                    objectValueText = "imperative mood",
                    scope = scope,
                    slotKey = "user.preferences.commit_style",
                    reasonToStore = "Stable explicit user preference for future coding work",
                    evidenceRefs = listOf("bundle:user_message:0"),
                    conflictPolicy = "SINGLE_ACTIVE_PER_SLOT",
                )
            )
        )
        val enabledRuntime = factory.create(userId = "user-a", requestId = "req-1")
        val factId = enabledRuntime.write(
            MemoryWriteInput(
                userMessage = "Remember that I want commit messages in imperative mood.",
                assistantMessage = "Understood.",
                scope = scope,
                turnRef = "turn-1",
                triggerType = MemoryTriggerType.EXPLICIT_REMEMBER_REQUEST,
            )
        ).acceptedFacts.single().id.orEmpty()

        settingsProvider.memoryEnabled = false
        val disabledRuntime = factory.create(userId = "user-a", requestId = "req-2")
        assertTrue(disabledRuntime.forgetFact(factId))

        settingsProvider.memoryEnabled = true
        val reenabledRuntime = factory.create(userId = "user-a", requestId = "req-3")
        val injection = reenabledRuntime.inject(
            MemoryInjectionRequest(
                queryText = "What commit style should you use?",
                scope = scope,
                turnRef = "turn-2",
            )
        )

        assertTrue(injection.renderedBlock.isBlank())
    }
}

private class TestBackendMemoryChatApi : ru.souz.llms.LLMChatAPI {
    private val queuedResponses = ArrayDeque<String>()

    fun enqueueProposal(json: String) {
        queuedResponses.addLast(json)
    }

    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat =
        LLMResponse.Chat.Ok(
            choices = listOf(
                LLMResponse.Choice(
                    message = LLMResponse.Message(
                        content = if (queuedResponses.isEmpty()) proposalEnvelope() else queuedResponses.removeFirst(),
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
            score(normalized, "commit", "imperative"),
            score(normalized, "style", "preference"),
            score(normalized, "memory", "remember"),
        )
    }

    private fun score(text: String, vararg terms: String): Double =
        terms.sumOf { term -> if (text.contains(term)) 1.0 else 0.0 }
}

private fun proposalEnvelope(vararg candidates: Map<String, Any?>): String =
    jacksonObjectMapper()
        .findAndRegisterModules()
        .writeValueAsString(mapOf("candidates" to candidates.toList()))

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
