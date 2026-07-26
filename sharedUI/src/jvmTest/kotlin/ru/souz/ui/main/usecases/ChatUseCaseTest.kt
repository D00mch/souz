@file:OptIn(ExperimentalCoroutinesApi::class)

package ru.souz.ui.main.usecases

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import ru.souz.agent.AgentFacade
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
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChatUseCaseTest {
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
    fun `onCleared emits pending conversation finish after active request is cancelled`() = runTest {
        val executeStarted = CompletableDeferred<Unit>()
        val executeResult = CompletableDeferred<String>()
        val finished = mutableListOf<Triple<String, ChatConversationMetrics, ChatConversationCloseReason>>()
        val agentFacade = mockk<AgentFacade>(relaxed = true)
        every { agentFacade.sideEffects } returns MutableSharedFlow<AgentSideEffect>()
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
        every { agentFacade.cancelActiveJob() } answers {
            executeResult.completeExceptionally(CancellationException("view model cleared"))
        }
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

        useCase.onCleared()
        requestJob.join()

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
        executeAnswer: suspend () -> String = { "response" },
    ): ChatUseCase {
        val agentFacade = mockk<AgentFacade>(relaxed = true)
        every { agentFacade.sideEffects } returns MutableSharedFlow<AgentSideEffect>()
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
        coEvery { agentFacade.executeForResult(any(), any()) } coAnswers {
            AgentExecutionResult(
                output = executeAnswer(),
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

        return ChatUseCase(
            agentFacade = agentFacade,
            settingsProvider = settingsProvider,
            speechUseCase = mockk(relaxed = true),
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
