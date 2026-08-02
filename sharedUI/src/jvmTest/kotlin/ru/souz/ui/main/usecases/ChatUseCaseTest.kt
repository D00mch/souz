@file:OptIn(ExperimentalCoroutinesApi::class)

package ru.souz.ui.main.usecases

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ru.souz.agent.AgentFacade
import ru.souz.agent.AgentId
import ru.souz.agent.AgentExecutionResult
import ru.souz.agent.AgentSideEffect
import ru.souz.agent.knowledge.ConversationKnowledgeStore
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.TokenLogging
import ru.souz.llms.ToolInvocationMeta
import ru.souz.memory.MemoryOwnerId
import ru.souz.memory.MemoryOwnerProvider
import ru.souz.memory.MemoryScope
import ru.souz.memory.MemoryService
import ru.souz.service.observability.ChatConversationCloseReason
import ru.souz.service.observability.ChatConversationMetrics
import ru.souz.service.observability.ChatObservabilityTracker
import ru.souz.service.observability.ChatRequestSource
import ru.souz.service.observability.DesktopStructuredLogger
import ru.souz.ui.main.MainState
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChatUseCaseTest {
    @Test
    fun `stale streamed chunk queued before continuation is not rendered after reset`() = runTest {
        val executeStarted = CompletableDeferred<Unit>()
        val executeResult = CompletableDeferred<String>()
        val sideEffects = MutableSharedFlow<AgentSideEffect>(extraBufferCapacity = 1)
        val useCase = createExecutableUseCase(
            activeAgentId = AgentId.SKILLS_GRAPH,
            sideEffects = sideEffects,
            submitToActiveRun = { true },
            executeAnswer = {
                executeStarted.complete(Unit)
                executeResult.await()
            },
        )
        var state = MainState()
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
            useCase.outputs.collect { output ->
                if (output is MainUseCaseOutput.State) {
                    state = output.reduce(state)
                }
            }
        }
        val requestJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase.sendChatMessage(
                scope = backgroundScope,
                isVoice = false,
                chatMessage = "hello",
                requestSource = ChatRequestSource.CHAT_UI,
            )
        }
        executeStarted.await()
        runCurrent()

        assertTrue(sideEffects.tryEmit(AgentSideEffect.Text("stale provisional")))
        val accepted = useCase.submitToActiveRun("steer this")
        runCurrent()

        assertTrue(accepted)
        assertEquals(listOf("hello", "steer this"), state.chatMessages.map { it.text })

        executeResult.complete("final answer")
        requestJob.join()
    }

    @Test
    fun `active run input replaces provisional stream without starting another request`() = runTest {
        val executeStarted = CompletableDeferred<Unit>()
        val executeResult = CompletableDeferred<String>()
        val executedInputs = mutableListOf<String>()
        val submittedInputs = mutableListOf<String>()
        val sideEffects = MutableSharedFlow<AgentSideEffect>()
        val useCase = createExecutableUseCase(
            activeAgentId = AgentId.SKILLS_GRAPH,
            sideEffects = sideEffects,
            executedInputs = executedInputs,
            submittedInputs = submittedInputs,
            submitToActiveRun = { true },
            executeAnswer = {
                executeStarted.complete(Unit)
                executeResult.await()
            },
        )
        var state = MainState()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase.outputs.collect { output ->
                if (output is MainUseCaseOutput.State) {
                    state = output.reduce(state)
                }
            }
        }

        val requestJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase.sendChatMessage(
                scope = backgroundScope,
                isVoice = false,
                chatMessage = "hello",
                requestSource = ChatRequestSource.CHAT_UI,
            )
        }
        executeStarted.await()
        runCurrent()
        sideEffects.emit(AgentSideEffect.Text("provisional"))

        assertEquals(listOf("hello", "provisional"), state.chatMessages.map { it.text })

        val accepted = useCase.submitToActiveRun("  steer this  ")

        assertTrue(accepted)
        assertEquals(listOf("hello", "steer this"), state.chatMessages.map { it.text })
        assertTrue(state.isProcessing)

        runCurrent()
        sideEffects.emit(AgentSideEffect.Text("replacement", streamRevision = 1L))
        assertEquals(listOf("hello", "steer this", "replacement"), state.chatMessages.map { it.text })
        assertFalse(state.chatMessages.any { it.text.contains("provisional") })

        executeResult.complete("final answer")
        requestJob.join()

        assertEquals(listOf("hello"), executedInputs)
        assertEquals(listOf("steer this"), submittedInputs)
        assertEquals(listOf("hello", "steer this", "final answer"), state.chatMessages.map { it.text })
        assertFalse(state.isProcessing)
    }

    @Test
    fun `voice continuation makes non-streaming response speak`() = runTest {
        val executeStarted = CompletableDeferred<Unit>()
        val executeResult = CompletableDeferred<String>()
        val speechUseCase = mockk<SpeechUseCase>(relaxed = true)
        val useCase = createExecutableUseCase(
            activeAgentId = AgentId.SKILLS_GRAPH,
            speechUseCase = speechUseCase,
            submitToActiveRun = { true },
            executeAnswer = {
                executeStarted.complete(Unit)
                executeResult.await()
            },
        )
        var state = MainState()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase.outputs.collect { output ->
                if (output is MainUseCaseOutput.State) {
                    state = output.reduce(state)
                }
            }
        }
        val requestJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase.sendChatMessage(
                scope = backgroundScope,
                isVoice = false,
                chatMessage = "typed request",
                requestSource = ChatRequestSource.CHAT_UI,
            )
        }
        executeStarted.await()

        assertTrue(useCase.submitToActiveRun("voice continuation", isVoice = true))
        executeResult.complete("spoken final answer")
        requestJob.join()

        assertEquals(
            listOf(false, true, true),
            state.chatMessages.map { it.isVoice },
        )
        verify(exactly = 1) { speechUseCase.queuePrepared("spoken final answer") }
    }

    @Test
    fun `streaming response follows latest continuation modality`() = runTest {
        val executeStarted = CompletableDeferred<Unit>()
        val executeResult = CompletableDeferred<String>()
        val sideEffects = MutableSharedFlow<AgentSideEffect>()
        val speechUseCase = mockk<SpeechUseCase>(relaxed = true)
        val useCase = createExecutableUseCase(
            activeAgentId = AgentId.SKILLS_GRAPH,
            sideEffects = sideEffects,
            speechUseCase = speechUseCase,
            useStreaming = true,
            submitToActiveRun = { true },
            executeAnswer = {
                executeStarted.complete(Unit)
                executeResult.await()
            },
        )
        var state = MainState()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase.outputs.collect { output ->
                if (output is MainUseCaseOutput.State) {
                    state = output.reduce(state)
                }
            }
        }
        val requestJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase.sendChatMessage(
                scope = backgroundScope,
                isVoice = false,
                chatMessage = "typed request",
                requestSource = ChatRequestSource.CHAT_UI,
            )
        }
        executeStarted.await()
        runCurrent()

        assertTrue(useCase.submitToActiveRun("voice continuation", isVoice = true))
        sideEffects.emit(AgentSideEffect.Text("spoken replacement", streamRevision = 1L))
        runCurrent()
        assertTrue(state.chatMessages.last().let { !it.isUser && it.isVoice })
        verify(exactly = 1) { speechUseCase.queuePrepared("spoken replacement") }

        assertTrue(useCase.submitToActiveRun("typed continuation"))
        sideEffects.emit(AgentSideEffect.Text("silent replacement", streamRevision = 2L))
        runCurrent()
        assertTrue(state.chatMessages.last().let { !it.isUser && !it.isVoice })
        verify(exactly = 0) { speechUseCase.queuePrepared("silent replacement") }

        executeResult.complete("silent final answer")
        requestJob.join()
        assertFalse(state.chatMessages.last().isVoice)
    }

    @Test
    fun `accepted active run input is rendered before a racing final response`() = runTest {
        val executeStarted = CompletableDeferred<Unit>()
        val executeResult = CompletableDeferred<String>()
        val useCase = createExecutableUseCase(
            activeAgentId = AgentId.SKILLS_GRAPH,
            submitToActiveRun = {
                executeResult.complete("final answer")
                yield()
                true
            },
            executeAnswer = {
                executeStarted.complete(Unit)
                executeResult.await()
            },
        )
        var state = MainState()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase.outputs.collect { output ->
                if (output is MainUseCaseOutput.State) {
                    state = output.reduce(state)
                }
            }
        }
        val requestJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase.sendChatMessage(
                scope = backgroundScope,
                isVoice = false,
                chatMessage = "hello",
                requestSource = ChatRequestSource.CHAT_UI,
            )
        }
        executeStarted.await()

        val accepted = useCase.submitToActiveRun("steer this")
        requestJob.join()

        assertTrue(accepted)
        assertEquals(
            listOf("hello", "steer this", "final answer"),
            state.chatMessages.map { it.text },
        )
        assertFalse(state.isProcessing)
    }

    @Test
    fun `superseded request removes continuations owned by the cancelled session`() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val firstResult = CompletableDeferred<String>()
        val firstCancellationObserved = CompletableDeferred<Unit>()
        val allowFirstCleanup = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val secondResult = CompletableDeferred<String>()
        val useCase = createExecutableUseCase(
            activeAgentId = AgentId.SKILLS_GRAPH,
            submitToActiveRun = { true },
            onCancelActiveJob = {
                if (firstStarted.isCompleted) {
                    firstResult.completeExceptionally(CancellationException("superseded"))
                }
            },
            executeAnswer = { input ->
                when (input) {
                    "first request" -> {
                        firstStarted.complete(Unit)
                        try {
                            firstResult.await()
                        } catch (error: CancellationException) {
                            firstCancellationObserved.complete(Unit)
                            allowFirstCleanup.await()
                            throw error
                        }
                    }
                    "second request" -> {
                        secondStarted.complete(Unit)
                        secondResult.await()
                    }
                    else -> error("Unexpected input: $input")
                }
            },
        )
        var state = MainState()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase.outputs.collect { output ->
                if (output is MainUseCaseOutput.State) {
                    state = output.reduce(state)
                }
            }
        }

        val firstRequest = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase.sendChatMessage(
                scope = backgroundScope,
                isVoice = false,
                chatMessage = "first request",
                requestSource = ChatRequestSource.CHAT_UI,
            )
        }
        firstStarted.await()
        assertTrue(useCase.submitToActiveRun("continuation"))

        val secondRequest = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase.sendChatMessage(
                scope = backgroundScope,
                isVoice = false,
                chatMessage = "second request",
                requestSource = ChatRequestSource.AMBIENT_AGENT,
            )
        }
        firstCancellationObserved.await()
        secondStarted.await()
        allowFirstCleanup.complete(Unit)
        firstRequest.join()

        assertEquals(listOf("second request"), state.chatMessages.map { it.text })
        assertTrue(state.isProcessing)

        secondResult.complete("second answer")
        secondRequest.join()
        assertEquals(listOf("second request", "second answer"), state.chatMessages.map { it.text })
    }

    @Test
    fun `conversation cleanup closes and deletes session plus legacy chat scopes`() = runTest {
        val owner = MemoryOwnerId("desktop-owner")
        val closedOwners = mutableListOf<MemoryOwnerId>()
        val closedScopes = mutableListOf<MemoryScope>()
        val deletedOwners = mutableListOf<MemoryOwnerId>()
        val deletedScopes = mutableListOf<MemoryScope>()
        val service = mockk<MemoryService>()
        coEvery { service.closeScopeForCapture(capture(closedOwners), capture(closedScopes)) } returns Unit
        coEvery { service.deleteFactsByScope(capture(deletedOwners), capture(deletedScopes)) } returns Unit
        val cleanup = MemoryServiceConversationCleanup(
            memoryService = service,
            ownerProvider = MemoryOwnerProvider { owner },
        )

        cleanup.cleanupConversation("chat-42")

        assertEquals(listOf(owner, owner), closedOwners)
        assertEquals(listOf(MemoryScope("session", "chat-42"), MemoryScope("chat", "chat-42")), closedScopes)
        assertEquals(listOf(owner, owner), deletedOwners)
        assertEquals(listOf(MemoryScope("session", "chat-42"), MemoryScope("chat", "chat-42")), deletedScopes)
        coVerify(exactly = 2) { service.closeScopeForCapture(any(), any()) }
        coVerify(exactly = 2) { service.deleteFactsByScope(any(), any()) }
    }

    @Test
    fun `onCleared emits pending conversation finish after request scope cancellation`() = runTest {
        val executeStarted = CompletableDeferred<Unit>()
        val executeResult = CompletableDeferred<String>()
        val finished = mutableListOf<Triple<String, ChatConversationMetrics, ChatConversationCloseReason>>()
        val agentFacade = mockk<AgentFacade>(relaxed = true)
        every { agentFacade.sideEffects } returns MutableSharedFlow<AgentSideEffect>()
        every { agentFacade.activeAgentId } returns MutableStateFlow(AgentId.GRAPH)
        every { agentFacade.currentContext } returns MutableStateFlow(
            AgentContext(
                input = "",
                settings = AgentSettings(
                    model = "model",
                    temperature = 0f,
                    toolsByCategory = emptyMap(),
                ),
                history = listOf(LLMRequest.Message(LLMMessageRole.system, "Base system prompt")),
                activeTools = emptyList(),
                systemPrompt = "Base system prompt",
            )
        )
        coEvery { agentFacade.executeForResult("hello", any()) } coAnswers {
            executeStarted.complete(Unit)
            AgentExecutionResult(
                output = executeResult.await(),
                context = agentFacade.currentContext.value,
            )
        }
        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        every { settingsProvider.gigaModel } returns LLMModel.Max
        val tokenLogging = mockk<TokenLogging>(relaxed = true)
        every { tokenLogging.requestContextElement(any()) } returns EmptyCoroutineContext
        every { tokenLogging.currentRequestTokenUsage(any()) } returns LLMResponse.Usage(1, 2, 3, 0)
        every { tokenLogging.sessionTokenUsage() } returns LLMResponse.Usage(1, 2, 3, 0)
        val tracker = ChatObservabilityTracker(
            onConversationStarted = { _, _ -> },
            onConversationFinished = { conversationId, metrics, reason ->
                finished += Triple(conversationId, metrics, reason)
            },
        )
        val useCase = ChatUseCase(
            agentFacade = agentFacade,
            settingsProvider = settingsProvider,
            speechUseCase = mockk(relaxed = true),
            finderPathExtractor = mockk(relaxed = true),
            chatAttachmentsUseCase = ChatAttachmentsUseCase(UnconfinedTestDispatcher()),
            toolModifyReviewUseCase = mockk(relaxed = true),
            observabilityTracker = tracker,
            log = DesktopStructuredLogger(),
            tokenLogging = tokenLogging,
            conversationKnowledgeStore = mockk(relaxed = true),
            ioDispatcher = UnconfinedTestDispatcher(),
        )

        val requestJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase.sendChatMessage(
                scope = this,
                isVoice = false,
                chatMessage = "hello",
                requestSource = ChatRequestSource.CHAT_UI,
            )
        }
        executeStarted.await()

        requestJob.cancel(CancellationException("view model cleared"))
        requestJob.join()
        useCase.onCleared()

        val event = finished.single()
        assertEquals(ChatConversationCloseReason.VIEW_MODEL_CLEARED, event.third)
        assertEquals(1, event.second.requestCount)
        assertEquals(3, event.second.tokenUsage.totalTokens)
    }

    @Test
    fun `sendChatMessage passes conversation and message ids to agent execution meta`() = runTest {
        val executionMeta = mutableListOf<ToolInvocationMeta?>()
        val agentFacade = mockk<AgentFacade>(relaxed = true)
        every { agentFacade.sideEffects } returns MutableSharedFlow<AgentSideEffect>()
        every { agentFacade.activeAgentId } returns MutableStateFlow(AgentId.GRAPH)
        every { agentFacade.currentContext } returns MutableStateFlow(
            AgentContext(
                input = "",
                settings = AgentSettings(
                    model = "model",
                    temperature = 0f,
                    toolsByCategory = emptyMap(),
                ),
                history = listOf(LLMRequest.Message(LLMMessageRole.system, "Base system prompt")),
                activeTools = emptyList(),
                systemPrompt = "Base system prompt",
            )
        )
        coEvery {
            agentFacade.executeForResult(
                input = "hello",
                toolInvocationMetaOverride = any(),
            )
        } coAnswers {
            executionMeta += secondArg<ToolInvocationMeta?>()
            AgentExecutionResult(
                output = "response",
                context = agentFacade.currentContext.value,
            )
        }

        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        every { settingsProvider.gigaModel } returns LLMModel.Max
        every { settingsProvider.notificationSoundEnabled } returns false
        every { settingsProvider.useStreaming } returns false

        val tokenLogging = mockk<TokenLogging>(relaxed = true)
        every { tokenLogging.requestContextElement(any()) } returns EmptyCoroutineContext
        every { tokenLogging.currentRequestTokenUsage(any()) } returns LLMResponse.Usage(0, 0, 0, 0)
        every { tokenLogging.sessionTokenUsage() } returns LLMResponse.Usage(0, 0, 0, 0)

        val toolModifyReviewUseCase = mockk<ToolModifyReviewUseCase>(relaxed = true)
        coEvery {
            toolModifyReviewUseCase.resolvePendingReviewIfNeeded(
                requestId = any(),
                pendingBotMessage = any(),
                response = "response",
                onReviewShown = any(),
            )
        } returns ToolModifyReviewUseCase.ToolModifyReviewResult(
            text = "response",
            appendAsNewMessage = false,
        )

        val useCase = ChatUseCase(
            agentFacade = agentFacade,
            settingsProvider = settingsProvider,
            speechUseCase = mockk(relaxed = true),
            finderPathExtractor = mockk(relaxed = true),
            chatAttachmentsUseCase = ChatAttachmentsUseCase(UnconfinedTestDispatcher()),
            toolModifyReviewUseCase = toolModifyReviewUseCase,
            observabilityTracker = ChatObservabilityTracker(log = DesktopStructuredLogger()),
            log = DesktopStructuredLogger(),
            tokenLogging = tokenLogging,
            conversationKnowledgeStore = mockk(relaxed = true),
            ioDispatcher = UnconfinedTestDispatcher(),
        )

        useCase.sendChatMessage(
            scope = backgroundScope,
            isVoice = false,
            chatMessage = "hello",
            requestSource = ChatRequestSource.CHAT_UI,
        )
        advanceUntilIdle()

        val meta = assertNotNull(executionMeta.single())
        assertTrue(meta.conversationId?.isNotBlank() == true)
        assertTrue(meta.requestId?.isNotBlank() == true)
        assertTrue(meta.attributes["userMessageId"]?.isNotBlank() == true)
        assertTrue(meta.attributes["assistantMessageId"]?.isNotBlank() == true)
    }

    @Test
    fun `cleanup does not wait for hanging agent execution without memory capture`() = runTest {
        val executeStarted = CompletableDeferred<Unit>()
        val executeResult = CompletableDeferred<String>()
        val cleanup = RecordingMemoryConversationCleanup()
        val useCase = createExecutableUseCase(
            cleanup = cleanup,
            executeAnswer = {
                executeStarted.complete(Unit)
                executeResult.await()
            },
        )

        backgroundScope.launch {
            useCase.sendChatMessage(
                scope = backgroundScope,
                isVoice = false,
                chatMessage = "hello",
                requestSource = ChatRequestSource.CHAT_UI,
            )
        }
        executeStarted.await()
        val conversationMeta = assertNotNull(
            useCase.finishCurrentConversation(ChatConversationCloseReason.NEW_CONVERSATION)
        )
        val conversationId = assertNotNull(conversationMeta.conversationId)

        useCase.clearConversationContext()
        useCase.cleanupConversation(conversationMeta)

        assertEquals(listOf(conversationId), cleanup.cleanedConversationIds)
        assertTrue(executeResult.isActive)
        executeResult.completeExceptionally(CancellationException("test cleanup"))
        advanceUntilIdle()
    }

    @Test
    fun `conversation cleanup uses exact execution metadata after context reset`() = runTest {
        val clearedKnowledgeMeta = mutableListOf<ToolInvocationMeta>()
        val knowledgeStore = mockk<ConversationKnowledgeStore>(relaxed = true)
        coEvery { knowledgeStore.clearConversation(capture(clearedKnowledgeMeta)) } returns Unit
        val baseMeta = ToolInvocationMeta(
            userId = "user-42",
            locale = "ru-RU",
            timeZone = "Europe/Moscow",
            attributes = mapOf("host" to "desktop"),
        )
        val useCase = createExecutableUseCase(
            knowledgeStore = knowledgeStore,
            baseMeta = baseMeta,
        )

        useCase.sendChatMessage(
            scope = backgroundScope,
            isVoice = false,
            chatMessage = "hello",
            requestSource = ChatRequestSource.CHAT_UI,
        )
        val capturedMeta = assertNotNull(
            useCase.finishCurrentConversation(ChatConversationCloseReason.NEW_CONVERSATION)
        )

        useCase.clearConversationContext()
        useCase.cleanupConversation(capturedMeta)

        assertEquals("user-42", capturedMeta.userId)
        assertEquals("ru-RU", capturedMeta.locale)
        assertEquals("Europe/Moscow", capturedMeta.timeZone)
        assertTrue(capturedMeta.conversationId?.isNotBlank() == true)
        assertTrue(capturedMeta.requestId?.isNotBlank() == true)
        assertEquals("desktop", capturedMeta.attributes["host"])
        assertTrue(capturedMeta.attributes["userMessageId"]?.isNotBlank() == true)
        assertTrue(capturedMeta.attributes["assistantMessageId"]?.isNotBlank() == true)
        assertEquals(listOf(capturedMeta), clearedKnowledgeMeta)
    }

    @Test
    fun `memory cleanup failure does not prevent Knowledge cleanup`() = runTest {
        val clearedKnowledgeMeta = mutableListOf<ToolInvocationMeta>()
        val knowledgeStore = mockk<ConversationKnowledgeStore>(relaxed = true)
        coEvery { knowledgeStore.clearConversation(capture(clearedKnowledgeMeta)) } returns Unit
        val useCase = createExecutableUseCase(
            cleanup = object : MemoryConversationCleanup {
                override suspend fun cleanupConversation(conversationId: String) {
                    error("memory failure")
                }
            },
            knowledgeStore = knowledgeStore,
        )
        val meta = ToolInvocationMeta(userId = "user-1", conversationId = "conversation-1")

        useCase.cleanupConversation(meta)

        assertEquals(listOf(meta), clearedKnowledgeMeta)
    }

    @Test
    fun `Knowledge cleanup failure is best effort and cancellation propagates`() = runTest {
        val failingStore = mockk<ConversationKnowledgeStore>(relaxed = true)
        coEvery { failingStore.clearConversation(any()) } throws IllegalStateException("storage failure")
        val cleanup = RecordingMemoryConversationCleanup()
        val useCase = createExecutableUseCase(cleanup = cleanup, knowledgeStore = failingStore)
        val meta = ToolInvocationMeta(userId = "user-1", conversationId = "conversation-1")

        useCase.cleanupConversation(meta)

        assertEquals(listOf("conversation-1"), cleanup.cleanedConversationIds)

        val cancellingStore = mockk<ConversationKnowledgeStore>(relaxed = true)
        coEvery { cancellingStore.clearConversation(any()) } throws CancellationException("cancelled")
        val cancellingUseCase = createExecutableUseCase(knowledgeStore = cancellingStore)

        assertFailsWith<CancellationException> {
            cancellingUseCase.cleanupConversation(meta)
        }
    }

    private fun createExecutableUseCase(
        cleanup: MemoryConversationCleanup = NoopMemoryConversationCleanup,
        knowledgeStore: ConversationKnowledgeStore = mockk(relaxed = true),
        baseMeta: ToolInvocationMeta = ToolInvocationMeta.localDefault(),
        activeAgentId: AgentId = AgentId.GRAPH,
        sideEffects: MutableSharedFlow<AgentSideEffect> = MutableSharedFlow(),
        speechUseCase: SpeechUseCase = mockk(relaxed = true),
        useStreaming: Boolean = false,
        executedInputs: MutableList<String>? = null,
        submittedInputs: MutableList<String>? = null,
        submitToActiveRun: suspend (String) -> Boolean = { false },
        onCancelActiveJob: () -> Unit = {},
        executeAnswer: suspend (String) -> String = { "response" },
    ): ChatUseCase {
        val agentFacade = mockk<AgentFacade>(relaxed = true)
        every { agentFacade.sideEffects } returns sideEffects
        every { agentFacade.activeAgentId } returns MutableStateFlow(activeAgentId)
        every { agentFacade.currentContext } returns MutableStateFlow(
            AgentContext(
                input = "",
                settings = AgentSettings(
                    model = "model",
                    temperature = 0f,
                    toolsByCategory = emptyMap(),
                ),
                history = listOf(LLMRequest.Message(LLMMessageRole.system, "Base system prompt")),
                activeTools = emptyList(),
                systemPrompt = "Base system prompt",
                toolInvocationMeta = baseMeta,
            )
        )
        coEvery { agentFacade.cancelActiveJob() } answers { onCancelActiveJob() }
        coEvery { agentFacade.executeForResult(any(), any()) } coAnswers {
            executedInputs?.add(firstArg())
            AgentExecutionResult(
                output = executeAnswer(firstArg()),
                context = agentFacade.currentContext.value,
            )
        }
        coEvery { agentFacade.submitToActiveRun(any()) } coAnswers {
            val input = firstArg<String>()
            submittedInputs?.add(input)
            submitToActiveRun(input)
        }

        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        every { settingsProvider.gigaModel } returns LLMModel.Max
        every { settingsProvider.notificationSoundEnabled } returns false
        every { settingsProvider.useStreaming } returns useStreaming

        val tokenLogging = mockk<TokenLogging>(relaxed = true)
        every { tokenLogging.requestContextElement(any()) } returns EmptyCoroutineContext
        every { tokenLogging.currentRequestTokenUsage(any()) } returns LLMResponse.Usage(0, 0, 0, 0)
        every { tokenLogging.sessionTokenUsage() } returns LLMResponse.Usage(0, 0, 0, 0)

        val toolModifyReviewUseCase = mockk<ToolModifyReviewUseCase>(relaxed = true)
        coEvery {
            toolModifyReviewUseCase.resolvePendingReviewIfNeeded(
                requestId = any(),
                pendingBotMessage = any(),
                response = any(),
                onReviewShown = any(),
            )
        } coAnswers {
            ToolModifyReviewUseCase.ToolModifyReviewResult(
                text = thirdArg(),
                appendAsNewMessage = false,
            )
        }

        return ChatUseCase(
            agentFacade = agentFacade,
            settingsProvider = settingsProvider,
            speechUseCase = speechUseCase,
            finderPathExtractor = mockk(relaxed = true),
            chatAttachmentsUseCase = ChatAttachmentsUseCase(UnconfinedTestDispatcher()),
            toolModifyReviewUseCase = toolModifyReviewUseCase,
            observabilityTracker = ChatObservabilityTracker(log = DesktopStructuredLogger()),
            log = DesktopStructuredLogger(),
            tokenLogging = tokenLogging,
            memoryConversationCleanup = cleanup,
            conversationKnowledgeStore = knowledgeStore,
            ioDispatcher = UnconfinedTestDispatcher(),
        )
    }

    private class RecordingMemoryConversationCleanup : MemoryConversationCleanup {
        val cleanedConversationIds = mutableListOf<String>()

        override suspend fun cleanupConversation(conversationId: String) {
            cleanedConversationIds += conversationId
        }
    }

}
